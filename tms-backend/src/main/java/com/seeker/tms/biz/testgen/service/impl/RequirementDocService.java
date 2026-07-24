package com.seeker.tms.biz.testgen.service.impl;

import com.seeker.tms.biz.testgen.entities.TestGenTaskPO;
import com.seeker.tms.biz.testgen.mapper.TestGenTaskMapper;
import com.seeker.tms.biz.testgen.model.ModelConfig;
import com.seeker.tms.biz.testgen.service.AiModelService;
import com.seeker.tms.biz.testgen.service.DocumentParserService;
import com.seeker.tms.biz.testgen.service.TestGenPromptService;
import com.seeker.tms.biz.testgen.utils.PromptLoader;
import com.seeker.tms.biz.testgen.websocket.TestGenWebSocketHandler;
import com.seeker.tms.common.docsource.DocContent;
import com.seeker.tms.common.docsource.DocFetchOptions;
import com.seeker.tms.common.docsource.DocumentLinkService;
import com.seeker.tms.common.llm.LlmClient;
import com.seeker.tms.common.utils.MinioUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 需求文档服务：负责把「主文档 + 关联文档」（上传文件 / 远程链接）下载、解析、整合成一份全量需求文本，
 * 并按任务维度缓存到 MinIO；同时维护根节点标题所需的文档标题信息。
 */
@Slf4j
@Component
@AllArgsConstructor
public class RequirementDocService {

    private static final int MAX_DOC_CHARS = 800000;

    /** 任务的文档标题（有序：主文档在前、关联文档在后，best-effort，用于根节点标题展示） */
    private static final Map<Integer, List<String>> docTitles = new ConcurrentHashMap<>();

    private final TestGenTaskMapper taskMapper;
    private final MinioUtil minioUtil;
    private final DocumentParserService documentParserService;
    private final DocumentLinkService documentLinkService;
    private final TestGenPromptService testGenPromptService;
    private final AiModelService aiModelService;
    private final LlmClient llmClient;

    public String fetchDocText(Integer taskId, String prdName) {
        return fetchDocText(taskId, prdName, null);
    }

    /** 已解析+整合后的全量需求文本缓存对象名 */
    public String parsedObjectName(Integer taskId) {
        return "testgen_" + taskId + ".parsed.txt";
    }

    /** 清理任务的已解析文本缓存，确保下次生成会按最新的主文档/关联文档/开关重新解析整合 */
    public void clearParsedCache(Integer taskId) {
        try {
            minioUtil.deleteFile(parsedObjectName(taskId));
        } catch (Exception e) {
            log.warn("清理已解析文本缓存失败, taskId={}: {}", taskId, e.toString());
        }
    }

    /** 清理任务的文档标题内存缓存 */
    public void clearTitles(Integer taskId) {
        docTitles.remove(taskId);
    }

    public String fetchDocText(Integer taskId, String prdName, String wsKey) {
        // 已解析文本按任务维度缓存（prdName 可能是多个文件名以换行拼接，不适合直接作为对象名）
        String parsedFileName = parsedObjectName(taskId);
        try {
            String parsedUrl = minioUtil.getInternalUrl(parsedFileName);
            byte[] parsedBytes = downloadFile(parsedUrl);
            if (parsedBytes != null && parsedBytes.length > 0) {
                if (wsKey != null) {
                    TestGenWebSocketHandler.sendProgress(wsKey, "使用已解析的文档内容");
                }
                return new String(parsedBytes, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            // MinIO 中未找到已解析文档，继续走解析逻辑
        }

        if (wsKey != null) {
            TestGenWebSocketHandler.sendProgress(wsKey, "正在解析文档内容...");
        }

        // 是否解析文档内图片由任务创建时的设置决定（图片输入创建时已强制关闭）
        TestGenTaskPO task = taskMapper.selectById(taskId);
        boolean parseImage = task != null && Boolean.TRUE.equals(task.getParseImage());
        boolean parseSubDoc = task != null && Boolean.TRUE.equals(task.getParseSubDoc());
        boolean isLink = task != null && "LINK".equalsIgnoreCase(task.getPrdSource());

        // 统一为「主文档 + 关联文档」：index0 为主文档
        // - LINK：单个主链接经通用能力抓取，parseSubDoc 时追加主文档引用的二级文档
        // - UPLOAD：主文档（prd_name）+ 关联文档（related_names）逐个解析后整合
        List<String> items = splitPrdNames(prdName);
        if (!isLink && task != null) {
            items.addAll(splitPrdNames(task.getRelatedNames()));
        }
        List<String> texts = new ArrayList<>();
        List<String> titles = new ArrayList<>();

        if (isLink) {
            String mainLink = items.isEmpty() ? null : items.get(0);
            if (mainLink != null) {
                if (wsKey != null) {
                    TestGenWebSocketHandler.sendProgress(wsKey,
                            parseSubDoc ? "正在抓取文档链接及其引用的二级文档..." : "正在抓取文档链接内容...");
                }
                List<DocContent> contents = documentLinkService.fetchAll(
                        mainLink, task.getCreator(), new DocFetchOptions(parseImage, parseSubDoc));
                for (DocContent c : contents) {
                    if (c == null || c.getText() == null || c.getText().isBlank()) continue;
                    texts.add(c.getText());
                    titles.add(c.getTitle());
                }
            }
        } else {
            for (int i = 0; i < items.size(); i++) {
                String name = items.get(i);
                if (wsKey != null && items.size() > 1) {
                    TestGenWebSocketHandler.sendProgress(wsKey,
                            "正在解析第 " + (i + 1) + "/" + items.size() + " 个文件："
                                    + name + (i == 0 ? "（主文档）" : "（关联文档）"));
                }
                // 上传文档以原始文件名存储在 MinIO，直接按文件名取用
                String prdUrl = minioUtil.getInternalUrl(name);
                String t = documentParserService.parseDocument(prdUrl, name, parseImage, (progress, message) -> {
                    if (wsKey != null) {
                        TestGenWebSocketHandler.sendProgress(wsKey, message);
                    }
                });
                if (t != null && !t.isBlank()) {
                    texts.add(t);
                    titles.add(stripExt(name));
                }
            }
        }

        // 记录有序标题（主在前），供根节点标题展示
        docTitles.put(taskId, titles);

        // 回写展示名：只用主文档名称（有序标题的首个），与链接来源保持一致，不拼接关联文档
        String mainTitle = null;
        for (String tt : titles) {
            if (tt != null && !tt.isBlank()) { mainTitle = tt.trim(); break; }
        }
        if (mainTitle != null) {
            try {
                TestGenTaskPO upd = new TestGenTaskPO();
                upd.setId(taskId);
                upd.setPrdDisplayName(mainTitle);
                taskMapper.updateById(upd);
            } catch (Exception e) {
                log.warn("回写需求文档展示名失败, taskId={}: {}", taskId, e.toString());
            }
        }

        // 收口：主文档 + 关联文档整合成一份全量需求文档
        String text;
        if (texts.isEmpty()) {
            text = "";
        } else if (texts.size() == 1) {
            text = texts.get(0);
        } else {
            if (wsKey != null) {
                TestGenWebSocketHandler.sendProgress(wsKey, "正在整合主文档与关联文档为一份全量需求...");
            }
            text = consolidateDocuments(texts, titles);
        }

        // 保存解析后的文本到 MinIO（任务维度）
        if (text != null && !text.isBlank()) {
            try {
                minioUtil.uploadFile(parsedFileName, text.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                log.error("保存解析后的文档到 MinIO 失败: {}", e.toString());
            }
        }

        return text != null ? text : "";
    }

    /** 拆分 prdName：支持多个文件名以换行拼接，去空白、去空项 */
    public List<String> splitPrdNames(String prdName) {
        List<String> names = new ArrayList<>();
        if (prdName == null || prdName.isBlank()) return names;
        for (String s : prdName.split("\\r?\\n")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) names.add(trimmed);
        }
        return names;
    }

    /**
     * 把「主文档 + 关联文档」的解析文本整合成一份完整、全量的需求文档。
     * index0 视为主文档（需求主体），其余为关联/补充材料，按实际关联关系有逻辑地合并。
     */
    private String consolidateDocuments(List<String> texts, List<String> titles) {
        StringBuilder combined = new StringBuilder();
        for (int i = 0; i < texts.size(); i++) {
            String label = (i < titles.size() && titles.get(i) != null && !titles.get(i).isBlank())
                    ? titles.get(i) : ("材料" + (i + 1));
            String role = (i == 0) ? "主文档" : ("关联文档" + i);
            combined.append("### ").append(role).append("：").append(label).append('\n')
                    .append(texts.get(i)).append("\n\n");
        }
        String system = testGenPromptService.getSystemPrompt("consolidate_system");
        String user = PromptLoader.loadWithParams("consolidate_user",
                Map.of("docs", truncateDocText(combined.toString())));
        return llmClient.streamToString(conn(), system, user, 600, 0.3);
    }

    /** 去掉文件名后缀作为标题；链接则返回空串（标题另由抓取内容提供） */
    private String stripExt(String name) {
        if (name == null) return "";
        if (name.startsWith("http://") || name.startsWith("https://")) return "";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private byte[] downloadFile(String url) {
        try {
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build();
            okhttp3.Request request = new okhttp3.Request.Builder().url(url).build();
            try (okhttp3.Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    return response.body().bytes();
                }
            }
        } catch (Exception e) {
            log.warn("下载文件失败: {}: {}", url, e.toString());
        }
        return null;
    }

    public String truncateDocText(String docText) {
        if (docText == null || docText.length() <= MAX_DOC_CHARS) {
            return docText;
        }
        log.warn("文档内容过长({} 字符)，截断至 {} 字符", docText.length(), MAX_DOC_CHARS);
        return docText.substring(0, MAX_DOC_CHARS) + "\n\n[文档内容过长，已截断...]";
    }

    /** 根节点/导出文件标题：优先主文档标题，回退展示名，再回退主文档文件名/通用名 */
    public String buildRootTitle(Integer taskId, String prdName) {
        // 只用主文档名称（有序标题的首个，主文档在前），不再拼接关联文档
        List<String> titles = taskId != null ? docTitles.get(taskId) : null;
        if (titles != null) {
            for (String t : titles) {
                if (t != null && !t.isBlank()) return t.trim();
            }
        }
        // 回退1：命中解析缓存时 docTitles 未填充（如重新生成复用缓存），用首次解析回写的展示名
        if (taskId != null) {
            TestGenTaskPO task = taskMapper.selectById(taskId);
            if (task != null && task.getPrdDisplayName() != null && !task.getPrdDisplayName().isBlank()) {
                return task.getPrdDisplayName().trim();
            }
        }
        // 回退2：从 prdName（主文档文件名或链接）推断
        List<String> names = splitPrdNames(prdName);
        if (names.isEmpty()) return "测试用例";
        String first = names.get(0);
        // 链接来源但无标题时，URL 不适合做标题，回退通用名
        if (first.startsWith("http://") || first.startsWith("https://")) {
            return "需求用例";
        }
        return stripExt(first);
    }

    private LlmClient.Conn conn() {
        ModelConfig cfg = aiModelService.getThinking();
        return new LlmClient.Conn(cfg.getBaseUrl(), cfg.getApiKey(), cfg.getModel());
    }
}
