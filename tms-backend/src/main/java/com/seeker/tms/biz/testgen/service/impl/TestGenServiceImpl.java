package com.seeker.tms.biz.testgen.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.seeker.tms.biz.testgen.config.LlmProperties;
import com.seeker.tms.biz.testgen.entities.*;
import com.seeker.tms.biz.testgen.enums.TaskStatus;
import com.seeker.tms.biz.testgen.mapper.TestGenTaskMapper;
import com.seeker.tms.biz.testgen.service.AgentChatService;
import com.seeker.tms.biz.testgen.service.DocumentParserService;
import com.seeker.tms.biz.testgen.service.TestGenService;
import com.seeker.tms.biz.testgen.utils.PromptLoader;
import com.seeker.tms.biz.testgen.websocket.TestGenWebSocketHandler;
import com.seeker.tms.common.utils.MinioUtil;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@AllArgsConstructor
public class TestGenServiceImpl extends ServiceImpl<TestGenTaskMapper, TestGenTaskPO>
        implements TestGenService {

    private static final String REDIS_KEY_XMIND = "testgen:task:%d:xmind";
    private static final String REDIS_KEY_CHAT = "testgen:task:%d:chat";
    private static final int REDIS_EXPIRE_HOURS = 72;
    private static final int MAX_DOC_CHARS = 800000;

    // 内存缓存：存储正在生成的任务的 XMind 树
    private static final Map<Integer, XMindNode> generatingTasks = new ConcurrentHashMap<>();

    // 跟踪正在生成用例的测试点（taskId -> Set<pointId>）
    private static final Map<Integer, Set<String>> generatingPoints = new ConcurrentHashMap<>();

    // 任务级别的锁，防止并发修改同一任务的树
    private static final Map<Integer, Object> taskLocks = new ConcurrentHashMap<>();

    private final TestGenTaskMapper taskMapper;
    private final AgentChatService agentChatService;
    private final DocumentParserService documentParserService;
    private final StringRedisTemplate redisTemplate;
    private final LlmProperties llmProperties;
    private final MinioUtil minioUtil;

    private Object getTaskLock(Integer taskId) {
        return taskLocks.computeIfAbsent(taskId, k -> new Object());
    }

    // ---- 任务管理 ----

    @Override
    public Integer createTask(TaskCreateDTO dto) {
        // 删除同名文件的已解析文档，确保使用最新内容
        if (dto.getPrdName() != null) {
            try {
                minioUtil.deleteFile(dto.getPrdName() + ".parsed.txt");
            } catch (Exception e) {
                // 文件可能不存在，忽略
            }
        }

        TestGenTaskPO task = new TestGenTaskPO();
        task.setPrdName(dto.getPrdName());
        task.setPrdType(dto.getPrdType());
        task.setStatus(TaskStatus.NEW.getCode());
        task.setCreateTime(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.insert(task);
        return task.getId();
    }

    @Override
    public List<TestGenTaskPO> listTasks() {
        return taskMapper.selectList(
                new LambdaQueryWrapper<TestGenTaskPO>().orderByDesc(TestGenTaskPO::getCreateTime));
    }

    @Override
    public TaskVO getTask(Integer taskId) {
        TestGenTaskPO task = taskMapper.selectById(taskId);
        return task == null ? null : BeanUtil.copyProperties(task, TaskVO.class);
    }

    // ---- Redis XMind 读写 ----

    @Override
    public XMindNode getXMindData(Integer taskId) {
        // 优先从内存缓存获取（生成中的任务）
        XMindNode generating = generatingTasks.get(taskId);
        if (generating != null) return generating;

        // 否则从 Redis 获取（已生成完成的任务）
        String key = String.format(REDIS_KEY_XMIND, taskId);
        String json = redisTemplate.opsForValue().get(key);
        if (json == null || json.isBlank()) return null;
        return JSON.parseObject(json, XMindNode.class);
    }

    @Override
    public void saveXMindData(Integer taskId, XMindNode root) {
        String key = String.format(REDIS_KEY_XMIND, taskId);
        redisTemplate.opsForValue().set(key, JSON.toJSONString(root), REDIS_EXPIRE_HOURS, TimeUnit.HOURS);
    }

    // ---- Agent：生成测试点（流式） ----

    @Override
    @Async("taskExecutor")
    public void generatePoints(Integer taskId) {
        String wsKey = String.valueOf(taskId);
        TestGenTaskPO task = taskMapper.selectById(taskId);
        if (task == null) return;

        try {
            // 1. 开始生成前：只记录任务状态
            updateStatus(taskId, TaskStatus.GENERATING.getCode(), "正在下载需求文档...");
            TestGenWebSocketHandler.sendProgress(wsKey, "正在下载需求文档...");

            String docText = fetchDocText(taskId, task.getPrdName(), wsKey);

            updateStatus(taskId, TaskStatus.GENERATING.getCode(), "正在提取测试点...");
            TestGenWebSocketHandler.sendProgress(wsKey, "正在提取测试点...");

            // 2. 初始化根节点，放入内存缓存
            XMindNode root = newNode("root", buildRootTitle(task.getPrdName()), "root");
            generatingTasks.put(taskId, root);

            // 3. 生成过程中：只在内存中累积，实时推送给前端
            callLlmStreaming(
                    buildPointExtractSystem(),
                    PromptLoader.loadWithParams("point_extract_user", Map.of(
                            "doc", truncateDocText(docText)
                    )),
                    (jsonObj) -> {
                        try {
                            addPointToTree(root, jsonObj);
                            // 不保存到 Redis，只推送给前端
                            TestGenWebSocketHandler.sendPointsGenerated(wsKey, root);
                        } catch (Exception ex) {
                            log.warn("处理单个测试点失败，跳过", ex);
                        }
                    }
            );

            // 4. 生成完成后：一次性保存到 Redis，并从内存缓存中移除
            int pointCount = countNodes(root, "point");
            saveXMindData(taskId, root);
            generatingTasks.remove(taskId);

            updateStatus(taskId, TaskStatus.EDITING.getCode(), "测试点生成完成，共 " + pointCount + " 个");
            TestGenWebSocketHandler.sendTaskStatus(wsKey, TaskStatus.EDITING.getCode(), "测试点生成完成，共 " + pointCount + " 个");
            TestGenWebSocketHandler.sendPointsGenerated(wsKey, root);

        } catch (Exception e) {
            log.error("生成测试点失败，taskId={}", taskId, e);
            generatingTasks.remove(taskId);
            updateStatus(taskId, TaskStatus.FAILED.getCode(), "失败：" + e.getMessage());
            TestGenWebSocketHandler.sendError(wsKey, e.getMessage());
        }
    }

    // ---- 单测试点生成用例（流式） ----

    @Override
    @Async("taskExecutor")
    public void generateCasesForPoint(Integer taskId, String pointId) {
        String wsKey = String.valueOf(taskId);
        Object lock = getTaskLock(taskId);

        // 注册生成中状态
        generatingPoints.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet()).add(pointId);

        try {
            TestGenTaskPO task = taskMapper.selectById(taskId);

            // 在锁内读取树并清空该测试点的子节点
            XMindNode pointNode;
            synchronized (lock) {
                XMindNode root = getXMindData(taskId);
                if (root == null) return;
                pointNode = findNodeById(root, pointId);
                if (pointNode == null) {
                    TestGenWebSocketHandler.sendError(wsKey, "测试点不存在");
                    return;
                }
                pointNode.setChildren(new ArrayList<>());
                saveXMindData(taskId, root);
            }

            String docText = fetchDocText(taskId, task.getPrdName());
            String pointTitle = pointNode.getTitle();
            String type = extractTypeFromTitle(pointTitle);

            // buildModulePath 需要 root，在锁内获取
            String module;
            synchronized (lock) {
                XMindNode root = getXMindData(taskId);
                module = buildModulePath(root, pointNode);
            }

            JSONObject pointPayload = new JSONObject();
            pointPayload.put("type", type);
            pointPayload.put("module", module);
            pointPayload.put("content", pointTitle.replaceFirst("^\\[.*?\\]\\s*", ""));

            callLlmStreaming(
                    buildCaseGenSystem(),
                    PromptLoader.loadWithParams("case_gen_user", Map.of(
                            "doc", truncateDocText(docText),
                            "points", JSON.toJSONString(pointPayload)
                    )),
                    (jsonObj) -> {
                        try {
                            XMindNode caseNode = buildSingleCaseNode(jsonObj);
                            if (caseNode != null) {
                                synchronized (lock) {
                                    XMindNode root = getXMindData(taskId);
                                    XMindNode pn = findNodeById(root, pointId);
                                    if (pn != null) {
                                        pn.getChildren().add(caseNode);
                                        saveXMindData(taskId, root);
                                        TestGenWebSocketHandler.sendPointCasesGenerated(wsKey, pointId, pn.getChildren(), false);
                                    }
                                }
                            }
                        } catch (Exception ex) {
                            log.warn("处理单个用例失败，跳过", ex);
                        }
                    }
            );

            // 生成完成
            synchronized (lock) {
                XMindNode root = getXMindData(taskId);
                XMindNode pn = findNodeById(root, pointId);
                TestGenWebSocketHandler.sendPointCasesGenerated(wsKey, pointId,
                        pn != null ? pn.getChildren() : List.of(), true);
            }
        } catch (Exception e) {
            log.error("单测试点生成用例失败, taskId={}, pointId={}", taskId, pointId, e);
            // 单个测试点失败不标记任务失败，只发送错误通知
            TestGenWebSocketHandler.sendError(wsKey, "测试点 " + pointId + " 生成失败: " + e.getMessage());
        } finally {
            Set<String> points = generatingPoints.get(taskId);
            if (points != null) {
                points.remove(pointId);
                if (points.isEmpty()) generatingPoints.remove(taskId);
            }
        }
    }

    // ---- 完成任务 ----

    @Override
    public String finishTask(Integer taskId) {
        XMindNode root = getXMindData(taskId);
        if (root == null) throw new RuntimeException("暂无数据");

        TestGenTaskPO taskPO = taskMapper.selectById(taskId);
        String fileName = buildRootTitle(taskPO.getPrdName()) + ".xmind";

        XMindNode exportRoot = rebuildForExport(root);
        byte[] xmindBytes = XMindBuilder.build(exportRoot);
        minioUtil.uploadFile(fileName, xmindBytes);

        TestGenTaskPO update = new TestGenTaskPO();
        update.setId(taskId);
        update.setStatus(TaskStatus.FINISHED.getCode());
        update.setXmindFileName(fileName);
        update.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(update);

        // 清理 Redis 缓存
        String xmindKey = String.format(REDIS_KEY_XMIND, taskId);
        String chatKey = String.format(REDIS_KEY_CHAT, taskId);
        redisTemplate.delete(List.of(xmindKey, chatKey));

        return fileName;
    }

    // ---- 重新生成 ----

    @Override
    public void regenerateTask(Integer taskId) {
        // 删除旧的 XMind 文件
        TestGenTaskPO oldTask = taskMapper.selectById(taskId);
        if (oldTask != null && oldTask.getXmindFileName() != null) {
            try {
                minioUtil.deleteFile(oldTask.getXmindFileName());
            } catch (Exception e) {
                log.warn("删除旧 XMind 文件失败: {}", oldTask.getXmindFileName(), e);
            }
        }

        generatingTasks.remove(taskId);
        generatingPoints.remove(taskId);
        taskLocks.remove(taskId);
        String xmindKey = String.format(REDIS_KEY_XMIND, taskId);
        String chatKey = String.format(REDIS_KEY_CHAT, taskId);
        redisTemplate.delete(List.of(xmindKey, chatKey));

        TestGenTaskPO task = new TestGenTaskPO();
        task.setId(taskId);
        task.setStatus(TaskStatus.NEW.getCode());
        task.setMessage(null);
        task.setXmindFileName(null);
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(task);
    }

    // ---- 恢复状态 ----

    @Override
    public RestoreVO restoreTask(Integer taskId) {
        RestoreVO vo = new RestoreVO();
        vo.setTask(getTask(taskId));
        vo.setTreeData(getXMindData(taskId));
        vo.setChatHistory(agentChatService.getChatHistory(taskId));
        Set<String> points = generatingPoints.get(taskId);
        vo.setGeneratingPointIds(points != null ? new ArrayList<>(points) : List.of());
        return vo;
    }

    // ============ 内部工具方法 ============

    private void updateStatus(Integer taskId, String status, String message) {
        TestGenTaskPO t = new TestGenTaskPO();
        t.setId(taskId);
        t.setStatus(status);
        t.setMessage(message);
        t.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(t);
    }


    private String fetchDocText(Integer taskId, String prdName) {
        return fetchDocText(taskId, prdName, null);
    }

    private String fetchDocText(Integer taskId, String prdName, String wsKey) {
        // 尝试从 MinIO 获取已解析的文本
        String parsedFileName = prdName + ".parsed.txt";
        try {
            String parsedUrl = minioUtil.getUrl(parsedFileName);
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

        // 未找到已解析文本，开始解析
        if (wsKey != null) {
            TestGenWebSocketHandler.sendProgress(wsKey, "正在解析文档内容...");
        }

        String prdUrl = minioUtil.getUrl(prdName);

        String text = documentParserService.parseDocument(prdUrl, prdName, (progress, message) -> {
            if (wsKey != null) {
                TestGenWebSocketHandler.sendProgress(wsKey, message);
            }
        });

        // 保存解析后的文本到 MinIO
        if (text != null && !text.isBlank()) {
            try {
                minioUtil.uploadFile(parsedFileName, text.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                log.error("保存解析后的文档到 MinIO 失败", e);
            }
        }

        return text != null ? text : "";
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
            log.warn("下载文件失败: {}", url, e);
        }
        return null;
    }

    private String truncateDocText(String docText) {
        if (docText == null || docText.length() <= MAX_DOC_CHARS) {
            return docText;
        }
        log.warn("文档内容过长({} 字符)，截断至 {} 字符", docText.length(), MAX_DOC_CHARS);
        return docText.substring(0, MAX_DOC_CHARS) + "\n\n[文档内容过长，已截断...]";
    }

    /**
     * 流式调用 LLM，每解析出一个完整 JSON 对象就回调
     */
    private void callLlmStreaming(String system, String user, java.util.function.Consumer<JSONObject> onItem) {
        LlmProperties.ModelConfig cfg = llmProperties.getThinking();
        OpenAiStreamingChatModel streamingModel = OpenAiStreamingChatModel.builder()
                .apiKey(cfg.getApiKey())
                .baseUrl(cfg.getBaseUrl())
                .modelName(cfg.getModel())
                .timeout(Duration.ofSeconds(180))
                .temperature(0.7)
                .build();

        StringBuilder buffer = new StringBuilder();
        CompletableFuture<Void> future = new CompletableFuture<>();

        streamingModel.generate(
                List.of(
                        dev.langchain4j.data.message.SystemMessage.from(system),
                        dev.langchain4j.data.message.UserMessage.from(user)
                ),
                new StreamingResponseHandler<>() {
                    @Override
                    public void onNext(String token) {
                        buffer.append(token);
                        // 尝试从 buffer 中提取完整的 JSON 对象
                        extractJsonObjects(buffer, onItem);
                    }

                    @Override
                    public void onComplete(Response<AiMessage> response) {
                        // 最后再尝试解析一次残留内容
                        extractJsonObjects(buffer, onItem);
                        future.complete(null);
                    }

                    @Override
                    public void onError(Throwable error) {
                        future.completeExceptionally(error);
                    }
                }
        );

        // 阻塞等待流式完成
        try {
            future.get(600, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("LLM 流式调用失败", e);
        }
    }

    /**
     * 从 buffer 中增量提取完整的 JSON 对象 {...}
     * 每提取到一个就回调 onItem，并从 buffer 中移除已解析的部分
     */
    private void extractJsonObjects(StringBuilder buffer, java.util.function.Consumer<JSONObject> onItem) {
        String content = buffer.toString();
        int searchFrom = 0;

        while (searchFrom < content.length()) {
            int braceStart = content.indexOf('{', searchFrom);
            if (braceStart == -1) break;

            // 找到匹配的 }
            int depth = 0;
            int braceEnd = -1;
            for (int i = braceStart; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        braceEnd = i;
                        break;
                    }
                }
            }

            if (braceEnd == -1) break; // 不完整，等待更多 token

            String jsonStr = content.substring(braceStart, braceEnd + 1);
            try {
                JSONObject obj = JSON.parseObject(jsonStr);
                onItem.accept(obj);
                // 移除已解析的部分
                content = content.substring(braceEnd + 1);
                buffer.setLength(0);
                buffer.append(content);
                searchFrom = 0;
            } catch (Exception e) {
                // 解析失败，可能是嵌套 JSON 导致截断不对，跳过这个 { 继续找下一个
                searchFrom = braceStart + 1;
            }
        }
    }

    /** 将单个测试点 JSON 添加到 XMind 树 */
    private void addPointToTree(XMindNode root, JSONObject pointJson) {
        String module = pointJson.getString("module");
        String type = pointJson.getString("type");
        String content = pointJson.getString("content");
        if (module == null || content == null) return;

        // 清理流式输出带来的换行符
        module = module.replaceAll("[\\n\\r]+", " ").trim();
        content = content.replaceAll("[\\n\\r]+", " ").trim();
        if (type != null) type = type.replaceAll("[\\n\\r]+", " ").trim();

        String[] layers = module.split("-");
        String topModule = layers[0].trim();
        String subModule = layers.length > 1 ? layers[1].trim() : null;

        // 查找或创建模块节点
        XMindNode moduleNode = findChildByTitle(root, topModule);
        if (moduleNode == null) {
            moduleNode = newNode("module_" + UUID.randomUUID(), topModule, "module");
            root.getChildren().add(moduleNode);
        }

        XMindNode targetNode = moduleNode;
        if (subModule != null) {
            XMindNode subNode = findChildByTitle(moduleNode, subModule);
            if (subNode == null) {
                subNode = newNode("module_" + UUID.randomUUID(), subModule, "module");
                moduleNode.getChildren().add(subNode);
            }
            targetNode = subNode;
        }

        XMindNode pointNode = newNode("point_" + UUID.randomUUID(),
                "[" + (type != null ? type : "功能检查") + "] " + content, "point");
        targetNode.getChildren().add(pointNode);
    }

    /** 构建单个用例节点 */
    private XMindNode buildSingleCaseNode(JSONObject c) {
        String caseName = c.getString("用例名称");
        if (caseName == null) return null;

        String priority = c.getString("优先级");
        String marker = null;
        if (priority != null && priority.startsWith("P")) {
            try {
                int level = Integer.parseInt(priority.substring(1));
                marker = "priority-" + (level + 1);
            } catch (NumberFormatException e) {
                log.warn("无法解析优先级: {}", priority);
            }
        }
        XMindNode caseNode = newNode("case_" + UUID.randomUUID(), caseName, "case");
        caseNode.setMarker(marker);
        caseNode.setExpanded(false);

        List<XMindNode> children = new ArrayList<>();
        String pre = c.getString("前置条件");
        if (pre != null && !pre.isBlank()) {
            children.add(newNode("step_" + UUID.randomUUID(), "前置条件: " + pre, "step"));
        }
        JSONArray steps = c.getJSONArray("测试步骤");
        if (steps != null) {
            for (int j = 0; j < steps.size(); j++) {
                JSONObject step = steps.getJSONObject(j);
                XMindNode stepNode = newNode("step_" + UUID.randomUUID(), step.getString("测试动作"), "step");
                String expected = step.getString("预期结果");
                if (expected != null && !expected.isBlank()) {
                    stepNode.setChildren(List.of(newNode("step_" + UUID.randomUUID(), expected, "step")));
                }
                children.add(stepNode);
            }
        }
        caseNode.setChildren(children);
        return caseNode;
    }

    private XMindNode findChildByTitle(XMindNode parent, String title) {
        if (parent.getChildren() == null) return null;
        for (XMindNode child : parent.getChildren()) {
            if (title.equals(child.getTitle())) return child;
        }
        return null;
    }

    private String extractTypeFromTitle(String title) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\[(.+?)\\]").matcher(title);
        return m.find() ? m.group(1) : "功能检查";
    }

    private String buildModulePath(XMindNode root, XMindNode targetNode) {
        List<String> path = new ArrayList<>();
        findNodePath(root, targetNode.getId(), path);
        return String.join("-", path);
    }

    private boolean findNodePath(XMindNode node, String targetId, List<String> path) {
        if (node.getId().equals(targetId)) {
            return true;
        }
        if (node.getChildren() != null) {
            for (XMindNode child : node.getChildren()) {
                if (findNodePath(child, targetId, path)) {
                    if (!"root".equals(node.getType())) {
                        path.add(0, node.getTitle());
                    }
                    return true;
                }
            }
        }
        return false;
    }

    private int countNodes(XMindNode node, String type) {
        int count = type.equals(node.getType()) ? 1 : 0;
        if (node.getChildren() != null) {
            for (XMindNode child : node.getChildren()) {
                count += countNodes(child, type);
            }
        }
        return count;
    }

    private XMindNode findNodeById(XMindNode node, String id) {
        if (node == null) return null;
        if (id.equals(node.getId())) return node;
        if (node.getChildren() != null) {
            for (XMindNode child : node.getChildren()) {
                XMindNode found = findNodeById(child, id);
                if (found != null) return found;
            }
        }
        return null;
    }

    private XMindNode newNode(String id, String title, String type) {
        XMindNode n = new XMindNode();
        n.setId(id);
        n.setTitle(title);
        n.setType(type);
        n.setExpanded(true);
        n.setChildren(new ArrayList<>());
        return n;
    }

    // ============ 导出树重组 ============

    /**
     * 检查树中是否存在自由节点（step 类型且不在 case 节点下）
     */
    private boolean hasFreeNodes(XMindNode node) {
        return hasFreeNodesRecursive(node, null);
    }

    private boolean hasFreeNodesRecursive(XMindNode node, String parentType) {
        if (node == null) return false;

        if ("free".equals(node.getType())) {
            return true;
        }

        if (node.getChildren() != null) {
            for (XMindNode child : node.getChildren()) {
                if (hasFreeNodesRecursive(child, node.getType())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 重组树结构用于 XMind 导出：
     * 1. 过滤 free 节点
     * 2. 去掉测试点节点，用例直接挂到模块下
     * 3. 按测试类型（功能检查/界面检查等）提升为根节点下第一级
     * 4. 保留所有模块节点（即使没有用例）
     * 导出结构：根 → 类型 → 模块路径 → 用例
     */
    private XMindNode rebuildForExport(XMindNode root) {
        // 收集所有模块路径和用例
        Map<String, Map<String, List<XMindNode>>> casesByType = new LinkedHashMap<>();
        Set<String> allModulePaths = new LinkedHashSet<>();
        collectModulesAndCases(root, new ArrayList<>(), casesByType, allModulePaths);

        XMindNode exportRoot = newNode(root.getId(), root.getTitle(), "root");

        // 按类型分组
        Map<String, Set<String>> modulesByType = new LinkedHashMap<>();
        for (String path : allModulePaths) {
            // 从路径中提取类型（路径格式：类型-模块1-模块2...）
            String[] parts = path.split("-", 2);
            if (parts.length > 0) {
                String type = parts[0];
                modulesByType.computeIfAbsent(type, k -> new LinkedHashSet<>()).add(path);
            }
        }

        // 构建导出树
        for (Map.Entry<String, Set<String>> typeEntry : modulesByType.entrySet()) {
            String type = typeEntry.getKey();
            XMindNode typeNode = newNode("type_" + UUID.randomUUID(), type, "module");

            // 构建该类型下的所有模块路径
            for (String fullPath : typeEntry.getValue()) {
                String[] parts = fullPath.split("-");
                XMindNode parent = typeNode;

                // 跳过第一个（类型），从第二个开始构建模块层级
                for (int i = 1; i < parts.length; i++) {
                    String moduleName = parts[i].trim();
                    if (moduleName.isEmpty()) continue;

                    XMindNode existing = findChildByTitle(parent, moduleName);
                    if (existing == null) {
                        existing = newNode("module_" + UUID.randomUUID(), moduleName, "module");
                        parent.getChildren().add(existing);
                    }
                    parent = existing;
                }

                // 挂载该路径下的用例
                Map<String, List<XMindNode>> casesMap = casesByType.get(type);
                if (casesMap != null) {
                    List<XMindNode> cases = casesMap.get(fullPath);
                    if (cases != null && !cases.isEmpty()) {
                        parent.getChildren().addAll(cases);
                    }
                }
            }

            exportRoot.getChildren().add(typeNode);
        }

        return exportRoot;
    }

    private void collectModulesAndCases(XMindNode node, List<String> modulePath,
                                        Map<String, Map<String, List<XMindNode>>> casesByType,
                                        Set<String> allModulePaths) {
        // 自由节点跳过
        if ("free".equals(node.getType())) return;

        if ("point".equals(node.getType())) {
            String type = extractTypeFromTitle(node.getTitle());
            String fullPath = type + (modulePath.isEmpty() ? "" : "-" + String.join("-", modulePath));

            // 记录该路径（即使没有用例）
            allModulePaths.add(fullPath);

            // 收集用例
            List<XMindNode> cases = node.getChildren() != null
                    ? node.getChildren().stream()
                          .filter(c -> "case".equals(c.getType()))
                          .collect(java.util.stream.Collectors.toList())
                    : List.of();

            if (!cases.isEmpty()) {
                casesByType.computeIfAbsent(type, k -> new LinkedHashMap<>())
                           .computeIfAbsent(fullPath, k -> new ArrayList<>())
                           .addAll(cases);
            }
            return;
        }

        if ("module".equals(node.getType())) {
            modulePath.add(node.getTitle());
        }

        if (node.getChildren() != null) {
            for (XMindNode child : node.getChildren()) {
                collectModulesAndCases(child, new ArrayList<>(modulePath), casesByType, allModulePaths);
            }
        }
    }

    // ============ Prompt 模板 ============

    private String buildRootTitle(String prdName) {
        // 从文件名中提取标题（去掉扩展名）
        if (prdName == null || prdName.isBlank()) return "测试用例";
        int dotIndex = prdName.lastIndexOf('.');
        return dotIndex > 0 ? prdName.substring(0, dotIndex) : prdName;
    }

    private String buildPointExtractSystem() {
        return PromptLoader.load("point_extract_system");
    }

    private String buildCaseGenSystem() {
        return PromptLoader.load("case_gen_system");
    }
}
