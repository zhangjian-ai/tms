package com.seeker.tms.biz.testgen.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.seeker.tms.biz.testgen.entities.*;
import com.seeker.tms.biz.testgen.enums.TaskStatus;
import com.seeker.tms.biz.testgen.mapper.TestGenTaskMapper;
import com.seeker.tms.biz.testgen.model.ModelConfig;
import com.seeker.tms.biz.testgen.service.TestGenPromptService;
import com.seeker.tms.biz.testgen.service.AiModelService;
import com.seeker.tms.biz.testgen.service.DocumentParserService;
import com.seeker.tms.biz.testgen.service.TestGenService;
import com.seeker.tms.biz.testgen.utils.PromptLoader;
import com.seeker.tms.biz.testgen.websocket.TestGenWebSocketHandler;
import com.seeker.tms.common.entities.PageResult;
import com.seeker.tms.common.docsource.DocContent;
import com.seeker.tms.common.docsource.DocFetchOptions;
import com.seeker.tms.common.docsource.DocumentLinkService;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@AllArgsConstructor
public class TestGenServiceImpl extends ServiceImpl<TestGenTaskMapper, TestGenTaskPO>
        implements TestGenService {

    private static final String REDIS_KEY_XMIND = "testgen:task:%d:xmind";
    private static final String REDIS_KEY_CHAT = "testgen:task:%d:chat";
    private static final String REDIS_KEY_OUTLINE = "testgen:task:%d:outline";
    private static final int REDIS_EXPIRE_HOURS = 72;
    private static final int MAX_DOC_CHARS = 800000;

    // 内存缓存：存储正在生成的任务的 XMind 树
    private static final Map<Integer, XMindNode> generatingTasks = new ConcurrentHashMap<>();

    // 跟踪正在生成用例的测试点（taskId -> Set<pointId>）
    private static final Map<Integer, Set<String>> generatingPoints = new ConcurrentHashMap<>();

    // 任务级别的锁，防止并发修改同一任务的树
    private static final Map<Integer, Object> taskLocks = new ConcurrentHashMap<>();

    // 任务的文档标题（有序：主文档在前、关联文档在后，best-effort，用于根节点标题展示）
    private static final Map<Integer, List<String>> docTitles = new ConcurrentHashMap<>();

    private final TestGenTaskMapper taskMapper;
    private final DocumentParserService documentParserService;
    private final DocumentLinkService documentLinkService;
    private final StringRedisTemplate redisTemplate;
    private final AiModelService aiModelService;
    private final TestGenPromptService testGenPromptService;
    private final MinioUtil minioUtil;

    private Object getTaskLock(Integer taskId) {
        return taskLocks.computeIfAbsent(taskId, k -> new Object());
    }

    // ---- 任务管理 ----

    @Override
    public Integer createTask(TaskCreateDTO dto) {
        String prdSource = "LINK".equalsIgnoreCase(dto.getPrdSource()) ? "LINK" : "UPLOAD";

        TestGenTaskPO task = new TestGenTaskPO();
        task.setPrdName(dto.getPrdName());
        task.setPrdSource(prdSource);
        task.setCreator(dto.getCreator());

        if ("LINK".equals(prdSource)) {
            // 链接来源：仅允许一个主文档链接；关联文档由「解析二级文档」从主文档中发现
            List<String> links = splitPrdNames(dto.getPrdName());
            if (links.size() != 1) {
                throw new IllegalArgumentException("文档链接来源仅支持填写一个主文档链接");
            }
            if (!documentLinkService.isSupported(links.get(0))) {
                throw new IllegalArgumentException("暂不支持的文档链接: " + links.get(0));
            }
            boolean parseImage = Boolean.TRUE.equals(dto.getParseImage());
            // 抓取的文档正文可能含图片；parseImage 决定是否下载并 OCR 回填
            aiModelService.ensureAvailable(parseImage);
            task.setParseImage(parseImage);
            task.setParseSubDoc(Boolean.TRUE.equals(dto.getParseSubDoc()));
        } else {
            // 上传来源：prd_name 只存主文档，关联文档单独存放，一并解析整合
            boolean parseImage = Boolean.TRUE.equals(dto.getParseImage());
            List<String> allNames = new ArrayList<>();
            if (dto.getPrdName() != null && !dto.getPrdName().isBlank()) allNames.add(dto.getPrdName().trim());
            List<String> relatedNames = new ArrayList<>();
            if (dto.getRelatedNames() != null) {
                for (String n : dto.getRelatedNames()) {
                    if (n != null && !n.isBlank()) relatedNames.add(n.trim());
                }
            }
            allNames.addAll(relatedNames);
            boolean anyImage = allNames.stream().anyMatch(documentParserService::isImageInput);
            boolean allImage = !allNames.isEmpty() && allNames.stream().allMatch(documentParserService::isImageInput);
            aiModelService.ensureAvailable(parseImage || anyImage);
            // 全部为图片时强制关闭图片解析（整图已独立解析，无内嵌图片可再解析）；
            // 含文档时保留用户选择，图片解析仅作用于文档内嵌图片，图片文件始终走独立整图解析。
            task.setParseImage(!allImage && parseImage);
            task.setParseSubDoc(false);
            task.setRelatedNames(relatedNames.isEmpty() ? null : String.join("\n", relatedNames));
        }

        task.setStatus(TaskStatus.NEW.getCode());
        task.setCreateTime(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.insert(task);

        // 强制清掉该 id 名下的旧文档缓存，避免新任务文档不生效
        clearParsedCache(task.getId());

        return task.getId();
    }

    @Override
    public PageResult<TestGenTaskPO> pageTasks(TaskQueryDTO query) {
        Page<TestGenTaskPO> page = Page.of(query.getPageNo(), query.getPageSize());
        // 默认按创建时间倒序；前端传 sortBy 时以其为准（sortBy 为数据库列名）
        String sortBy = (query.getSortBy() != null && !query.getSortBy().isBlank())
                ? query.getSortBy() : "create_time";
        page.addOrder(new OrderItem(sortBy, query.isAsc()));

        this.lambdaQuery().page(page);

        PageResult<TestGenTaskPO> result = new PageResult<>();
        result.setTotal((int) page.getTotal());
        result.setPageNo((int) page.getCurrent());
        result.setPageCount((int) page.getPages());
        result.setList(page.getRecords());
        return result;
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
            // 阶段 A：规划
            updateStatus(taskId, TaskStatus.PLANNING.getCode(), "正在下载并解析需求文档...");
            TestGenWebSocketHandler.sendTaskStatus(wsKey,
                    TaskStatus.PLANNING.getCode(), "正在下载并解析需求文档...");
            TestGenWebSocketHandler.sendPhaseChanged(wsKey, "PLANNING", "正在下载并解析需求文档...");
            TestGenWebSocketHandler.sendProgress(wsKey, "正在下载需求文档...");

            String docText = fetchDocText(taskId, task.getPrdName(), wsKey);

            updateStatus(taskId, TaskStatus.PLANNING.getCode(), "正在生成需求章节摘要...");
            TestGenWebSocketHandler.sendProgress(wsKey, "正在生成需求章节摘要...");

            OutlineVO outline = callPlanningAgent(truncateDocText(docText));

            // 暂存大纲并通知前端
            saveOutline(taskId, outline);
            updateStatus(taskId, TaskStatus.PLAN_REVIEW.getCode(), "章节摘要已生成，等待确认");
            TestGenWebSocketHandler.sendPhaseChanged(wsKey, "PLAN_REVIEW", "请确认或调整需求章节大纲");
            TestGenWebSocketHandler.sendPlanDrafted(wsKey, outline);
            TestGenWebSocketHandler.sendTaskStatus(wsKey, TaskStatus.PLAN_REVIEW.getCode(), "章节摘要已生成，等待确认");
        } catch (Throwable e) {
            log.error("生成大纲失败，taskId={}: {}", taskId, e.toString());
            String reason = describeThrowable(e);
            updateStatus(taskId, TaskStatus.FAILED.getCode(), "失败：" + reason);
            TestGenWebSocketHandler.sendError(wsKey, reason);
        }
    }

    @Override
    public OutlineVO getOutline(Integer taskId) {
        String key = String.format(REDIS_KEY_OUTLINE, taskId);
        String json = redisTemplate.opsForValue().get(key);
        if (json == null || json.isBlank()) return null;
        return JSON.parseObject(json, OutlineVO.class);
    }

    private void saveOutline(Integer taskId, OutlineVO outline) {
        String key = String.format(REDIS_KEY_OUTLINE, taskId);
        redisTemplate.opsForValue().set(key, JSON.toJSONString(outline), REDIS_EXPIRE_HOURS, TimeUnit.HOURS);
    }

    @Override
    @Async("taskExecutor")
    public void confirmPlan(Integer taskId, OutlineVO outline) {
        String wsKey = String.valueOf(taskId);
        TestGenTaskPO task = taskMapper.selectById(taskId);
        if (task == null) return;

        // 用户可能调整过大纲，以传入为准；空则走暂存版本
        OutlineVO effective = outline != null ? outline : getOutline(taskId);
        if (effective == null || effective.getChapters() == null || effective.getChapters().isEmpty()) {
            TestGenWebSocketHandler.sendError(wsKey, "大纲为空，请先生成或填写章节");
            // 还原状态，避免前端因预设 GENERATING 而卡在遮罩
            TestGenWebSocketHandler.sendTaskStatus(wsKey,
                    TaskStatus.PLAN_REVIEW.getCode(), "大纲为空，请先生成或填写章节");
            return;
        }
        saveOutline(taskId, effective);

        try {
            updateStatus(taskId, TaskStatus.GENERATING.getCode(), "正在按章节提取测试点...");
            TestGenWebSocketHandler.sendTaskStatus(wsKey,
                    TaskStatus.GENERATING.getCode(), "正在按章节提取测试点...");
            TestGenWebSocketHandler.sendPhaseChanged(wsKey, "EXTRACTING", "正在按章节提取测试点...");

            String docText = fetchDocText(taskId, task.getPrdName(), wsKey);
            String truncatedDoc = truncateDocText(docText);
            String summary = effective.getSummary() == null ? "" : effective.getSummary();

            XMindNode root = newNode("root", buildRootTitle(taskId, task.getPrdName()), "root");
            generatingTasks.put(taskId, root);

            List<OutlineVO.Chapter> chapters = effective.getChapters();

            // 解析提取阶段系统提示词，本批章节复用一份
            String extractSystem = testGenPromptService.getSystemPrompt("extract_points_system");
            int failed = parallelExtractModules(wsKey, taskId, root, chapters, summary, truncatedDoc, extractSystem);

            int pointCount = countNodes(root, "point");
            saveXMindData(taskId, root);

            String extractDoneMsg = "测试点提取完成，共 " + pointCount + " 个"
                    + (failed > 0 ? "（" + failed + " 个模块失败）" : "");
            log.info("taskId={} 测试点提取完成，共 {} 个", taskId, pointCount);
            updateStatus(taskId, TaskStatus.GENERATING.getCode(), extractDoneMsg);
            TestGenWebSocketHandler.sendTaskStatus(wsKey, TaskStatus.GENERATING.getCode(), extractDoneMsg);
            TestGenWebSocketHandler.sendProgress(wsKey, extractDoneMsg);
            TestGenWebSocketHandler.sendPointsGenerated(wsKey, root);

            // 阶段 B-2：自动反思（去重 + 补漏），完成后再推一次树
            // 精修是"尽力而为"的增强步骤，任何失败（含 Error）都不应阻断后续的用例生成
            try {
                refinePoints(taskId, wsKey, effective, truncatedDoc);
            } catch (Throwable ex) {
                log.warn("taskId={} 自动反思失败，跳过精修，直接进入用例生成阶段: {}", taskId, ex.toString());
                TestGenWebSocketHandler.sendProgress(wsKey, "自动精修失败，跳过：" + describeThrowable(ex));
            }

            XMindNode refinedRoot = getXMindData(taskId);
            int refinedPointCount = refinedRoot != null ? countNodes(refinedRoot, "point") : pointCount;
            String autoCaseMsg = "测试点精修完成，共 " + refinedPointCount + " 个，开始自动生成用例";
            updateStatus(taskId, TaskStatus.GENERATING.getCode(), autoCaseMsg);
            TestGenWebSocketHandler.sendTaskStatus(wsKey, TaskStatus.GENERATING.getCode(), autoCaseMsg);
            TestGenWebSocketHandler.sendPhaseChanged(wsKey, "GENERATING_CASES", autoCaseMsg);
            TestGenWebSocketHandler.sendProgress(wsKey, autoCaseMsg);
            if (refinedRoot != null) {
                TestGenWebSocketHandler.sendPointsGenerated(wsKey, refinedRoot);
            }

            // 阶段 C：并发为每个 point 生成用例，完成后折叠为模块下的用例
            autoGenerateCasesForAllPoints(taskId);
            log.info("taskId={} 自动生成用例阶段完成", taskId);

            XMindNode finalRoot = getXMindData(taskId);
            generatingTasks.remove(taskId);

            // 精修（去重/补漏）后可能残留没有任何测试点/用例的空目录，导出前统一清理
            if (finalRoot != null) {
                int prunedDirs = pruneEmptyModules(finalRoot);
                if (prunedDirs > 0) {
                    saveXMindData(taskId, finalRoot);
                    log.info("taskId={} 清理空目录 {} 个", taskId, prunedDirs);
                }
            }

            int caseCount = finalRoot != null ? countNodes(finalRoot, "case") : 0;
            int failedPoints = finalRoot != null ? countFailedPoints(finalRoot) : 0;
            String doneMsg = "用例生成完成，共 " + caseCount + " 条用例"
                    + (failedPoints > 0 ? "（" + failedPoints + " 个测试点失败，可右键单独重试）" : "");
            updateStatus(taskId, TaskStatus.EDITING.getCode(), doneMsg);
            TestGenWebSocketHandler.sendTaskStatus(wsKey, TaskStatus.EDITING.getCode(), doneMsg);
            TestGenWebSocketHandler.sendPointsGenerated(wsKey, finalRoot);
            TestGenWebSocketHandler.sendPhaseChanged(wsKey, "EDITING", "用例生成完成");
        } catch (Throwable e) {
            log.error("提取测试点失败，taskId={}: {}", taskId, e.toString());
            generatingTasks.remove(taskId);
            String reason = describeThrowable(e);
            updateStatus(taskId, TaskStatus.FAILED.getCode(), "失败：" + reason);
            TestGenWebSocketHandler.sendError(wsKey, reason);
        }
    }

    /**
     * 并发为当前任务下所有 point 节点生成用例，完成后自动折叠。
     * 失败的 point 节点保留并标记 failed，由用户手动重试。
     */
    private void autoGenerateCasesForAllPoints(Integer taskId) {
        XMindNode root = getXMindData(taskId);
        if (root == null) return;
        List<XMindNode> chapterNodes = new ArrayList<>();
        collectChapterModules(root, chapterNodes);
        if (chapterNodes.isEmpty()) return;

        String wsKey = String.valueOf(taskId);
        int total = chapterNodes.size();
        TestGenWebSocketHandler.sendProgress(wsKey, "开始并发生成用例，共 " + total + " 个章节...");

        String caseSystem = testGenPromptService.getSystemPrompt("case_gen_system");

        int concurrency = Math.min(4, total);
        ExecutorService pool = Executors.newFixedThreadPool(concurrency, r -> {
            Thread t = new Thread(r, "case-gen-" + taskId);
            t.setDaemon(true);
            return t;
        });
        AtomicInteger done = new AtomicInteger(0);
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>(total);
            for (XMindNode cn : chapterNodes) {
                String chapterNodeId = cn.getId();
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        generateCasesForChapter(taskId, chapterNodeId, caseSystem);
                    } catch (Exception ex) {
                        log.warn("用例生成任务异常 taskId={} chapterNodeId={}: {}", taskId, chapterNodeId, ex.toString());
                    } finally {
                        int finished = done.incrementAndGet();
                        TestGenWebSocketHandler.sendProgress(wsKey,
                                "已完成 " + finished + "/" + total + " 个章节的用例生成");
                    }
                }, pool));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            int failedPoints = countFailedPoints(getXMindData(taskId));
            TestGenWebSocketHandler.sendProgress(wsKey,
                    "用例生成阶段完成"
                            + (failedPoints > 0 ? "：" + failedPoints + " 个测试点失败，可在面板上右键单独重试" : ""));
        } finally {
            pool.shutdown();
        }
    }

    /** 收集章节节点：每个 category（root 直接子节点）下的一级模块，且含至少一个测试点 */
    private void collectChapterModules(XMindNode root, List<XMindNode> out) {
        if (root == null || root.getChildren() == null) return;
        for (XMindNode category : root.getChildren()) {
            if (category.getChildren() == null) continue;
            for (XMindNode chapter : category.getChildren()) {
                if (!"module".equals(chapter.getType())) continue;
                if (countNodes(chapter, "point") > 0) out.add(chapter);
            }
        }
    }


    private void collectPointIds(XMindNode node, List<String> out) {
        if (node == null) return;
        if ("point".equals(node.getType())) {
            out.add(node.getId());
            return; // point 下不再递归
        }
        if (node.getChildren() != null) {
            for (XMindNode child : node.getChildren()) collectPointIds(child, out);
        }
    }

    /**
     * 并发为大纲中的每个章节抽取测试点；写树通过 taskLock 保证线程安全。
     * 返回失败章节数。
     */
    private int parallelExtractModules(String wsKey, Integer taskId, XMindNode root,
                                       List<OutlineVO.Chapter> chapters,
                                       String summary, String docText, String extractSystem) {
        // 过滤空章节名，并保留原索引用于进度文案
        List<OutlineVO.Chapter> effective = new ArrayList<>();
        for (OutlineVO.Chapter c : chapters) {
            String n = c.getName() == null ? "" : c.getName().trim();
            if (!n.isEmpty()) effective.add(c);
        }
        int total = effective.size();
        if (total == 0) return 0;

        // 给每个章节打上内部ID：提取出的测试点据此关联所属章节，供一级精修按章节分组（不下发大模型）
        for (OutlineVO.Chapter c : effective) {
            if (c.getId() == null || c.getId().isBlank()) c.setId("chapter_" + UUID.randomUUID());
        }

        TestGenWebSocketHandler.sendProgress(wsKey, "开始并发提取测试点，共 " + total + " 个章节...");

        int concurrency = Math.min(4, total);
        ExecutorService pool = Executors.newFixedThreadPool(concurrency, r -> {
            Thread t = new Thread(r, "chapter-extract-" + taskId);
            t.setDaemon(true);
            return t;
        });
        AtomicInteger done = new AtomicInteger(0);
        AtomicInteger failedCnt = new AtomicInteger(0);
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>(total);
            for (OutlineVO.Chapter c : effective) {
                futures.add(CompletableFuture.runAsync(() -> {
                    String chapterName = c.getName().trim();
                    try {
                        extractModulePoints(wsKey, taskId, root, c, summary, docText, extractSystem);
                    } catch (Exception ex) {
                        failedCnt.incrementAndGet();
                        log.warn("章节 [{}] 提取失败，继续其他章节: {}", chapterName, ex.toString());
                        TestGenWebSocketHandler.sendProgress(wsKey,
                                "章节 " + chapterName + " 提取失败：" + ex.getMessage());
                    } finally {
                        int finished = done.incrementAndGet();
                        TestGenWebSocketHandler.sendProgress(wsKey,
                                "已完成 " + finished + "/" + total + " 个章节的测试点提取");
                    }
                }, pool));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            pool.shutdown();
        }
        return failedCnt.get();
    }

    /** 单章节提取：流式调用，每解析出一个测试点立即挂树并推送；失败重试 1 次 */
    private void extractModulePoints(String wsKey, Integer taskId, XMindNode root,
                                     OutlineVO.Chapter chapter,
                                     String summary, String docText, String extractSystem) {
        String chapterName = chapter.getName() == null ? "" : chapter.getName().trim();
        String chapterScope = chapter.getScope() == null ? "" : chapter.getScope();

        Map<String, String> params = new HashMap<>();
        params.put("chapterName", chapterName);
        params.put("chapterScope", chapterScope);
        params.put("summary", summary);
        params.put("doc", docText);

        String user = PromptLoader.loadWithParams("extract_points_user", params);
        Object lock = getTaskLock(taskId);

        // 失败重试 1 次：限流/瞬态网络抖动场景能自愈。
        // 注意：0 个测试点不视为失败（该章节可能确实无测试点），仅在异常时重试并最终抛出。
        int maxAttempts = 2;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            // 记录本次尝试新增的测试点 id，重试前据此清理，避免与下一次结果叠加
            List<String> addedThisAttempt = new ArrayList<>();
            try {
                callLlmStreaming(extractSystem, user, (jsonObj) -> {
                    try {
                        synchronized (lock) {
                            bindModuleToOutline(jsonObj, chapterName);
                            String newPointId = addPointToTree(root, jsonObj, chapter.getId());
                            if (newPointId != null) {
                                addedThisAttempt.add(newPointId);
                                TestGenWebSocketHandler.sendPointAdded(wsKey, root, newPointId);
                            }
                        }
                    } catch (Exception ex) {
                        log.warn("处理章节[{}]单个测试点失败，跳过: {}", chapterName, ex.toString());
                    }
                });
                // 流式正常结束即视为成功（含 0 个测试点的合法情况）
                return;
            } catch (Exception ex) {
                log.warn("章节[{}] 第 {} 次提取失败：{}", chapterName, attempt, ex.getMessage());
                if (attempt >= maxAttempts) {
                    throw new RuntimeException("章节[" + chapterName + "]测试点提取失败：" + ex.getMessage(), ex);
                }
                // 重试前清空本次已写入的测试点并刷新前端，再退避
                synchronized (lock) {
                    for (String pid : addedThisAttempt) {
                        removeNodeById(root, pid);
                    }
                }
                TestGenWebSocketHandler.sendPointsGenerated(wsKey, root);
                try { Thread.sleep(1500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
    }

    /**
     * 调用规划 Agent，期望返回单个 OutlineVO 形态的 JSON 对象。
     */
    private OutlineVO callPlanningAgent(String docText) {
        String system = testGenPromptService.getSystemPrompt("planning_system");
        String user = PromptLoader.loadWithParams("planning_user", Map.of("doc", docText));
        String response = runStreamingToString(system, user, 600, 0.5);
        return parseOutlineResponse(response);
    }

    /** 从模型回复中提取首个完整 JSON 对象并解析为 OutlineVO */
    private OutlineVO parseOutlineResponse(String text) {
        if (text == null || text.isBlank()) {
            throw new RuntimeException("规划返回为空");
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new RuntimeException("规划返回不是合法 JSON：" + text);
        }
        String json = text.substring(start, end + 1);
        try {
            return JSON.parseObject(json, OutlineVO.class);
        } catch (Exception e) {
            throw new RuntimeException("规划返回解析失败：" + json, e);
        }
    }

    // ---- 自动精修测试点（去重 · 合并 · 补漏） ----

    /**
     * 两级精修：① 按章节并行去重+补漏；② 全局去重合并
     */
    private void refinePoints(Integer taskId, String wsKey,
                              OutlineVO outline, String truncatedDoc) {
        XMindNode root = getXMindData(taskId);
        if (root == null) return;
        if (collectPointsFull(root).isEmpty()) {
            log.info("taskId={} 现有测试点为空，跳过自动精修", taskId);
            return;
        }

        TestGenWebSocketHandler.sendPhaseChanged(wsKey, "REFINING", "AI 正在精修测试点（去重 · 合并 · 补漏）...");
        refineByChapter(taskId, wsKey, outline, truncatedDoc);
        refineGlobal(taskId, wsKey, outline, truncatedDoc);
    }

    /** 章节级：每个章节独立去重 + 补漏（并行） */
    private void refineByChapter(Integer taskId, String wsKey, OutlineVO outline, String doc) {
        XMindNode root = getXMindData(taskId);
        if (root == null) return;

        // 章节ID -> 章节（名称/摘要），用于分组与提示词入参
        Map<String, OutlineVO.Chapter> chapterById = new HashMap<>();
        Map<String, String> scopeByName = new HashMap<>();
        if (outline != null && outline.getChapters() != null) {
            for (OutlineVO.Chapter c : outline.getChapters()) {
                if (c.getId() != null) chapterById.put(c.getId(), c);
                if (c.getName() != null) scopeByName.put(tidyTitle(c.getName()), c.getScope() == null ? "" : c.getScope());
            }
        }

        // 测试点 -> 所属章节ID（逻辑层旁路，不进入下发大模型的 points 载荷）
        Map<String, String> pointToChapter = new HashMap<>();
        collectPointChapterIds(root, pointToChapter);

        // 按章节ID分组；无 chapterId 的点（补漏点/历史数据）回退到 module 首段名分组
        Map<String, List<Map<String, String>>> byChapter = new LinkedHashMap<>();
        for (Map<String, String> p : collectPointsFull(root)) {
            String key = pointToChapter.get(p.get("id"));
            if (key == null || key.isBlank()) {
                String module = p.getOrDefault("module", "");
                key = module.isBlank() ? "未归类" : module.split("-")[0].trim();
                if (key.isBlank()) key = "未归类";
            }
            byChapter.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }
        if (byChapter.isEmpty()) return;

        TestGenWebSocketHandler.sendProgress(wsKey, "章节内精修（去重 · 合并 · 补漏），共 " + byChapter.size() + " 个章节...");
        List<Map<String, String>> paramsList = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, String>>> e : byChapter.entrySet()) {
            OutlineVO.Chapter oc = chapterById.get(e.getKey());
            String chapterName = oc != null ? oc.getName() : e.getKey();
            String chapterScope = oc != null
                    ? (oc.getScope() == null ? "" : oc.getScope())
                    : scopeByName.getOrDefault(e.getKey(), "");
            Map<String, String> params = new HashMap<>();
            params.put("chapterName", chapterName);
            params.put("chapterScope", chapterScope);
            params.put("points", JSON.toJSONString(e.getValue()));
            params.put("doc", doc);
            paramsList.add(params);
        }

        String system = testGenPromptService.getSystemPrompt("refine_chapter_system");
        RefineBatch batch = runRefineCalls(taskId, paramsList, system, "refine_chapter_user");

        Object lock = getTaskLock(taskId);
        int removed = 0, added = 0;
        synchronized (lock) {
            XMindNode current = getXMindData(taskId);
            if (current == null) return;
            for (JSONObject res : batch.results) {
                if (res == null) continue;
                removed += applyDuplicateRemovals(current, res.getJSONArray("duplicateGroups"));
                added += applyAdditions(current, res.getJSONArray("additions"));
            }
            saveXMindData(taskId, current);
        }
        String msg = "章节内精修完成：去重/合并 " + removed + " 条，补齐 " + added + " 条"
                + (batch.hasFailure() ? "（" + batch.errors.size() + " 个章节精修失败已跳过：" + batch.firstError() + "）" : "");
        log.info("taskId={} {}", taskId, msg);
        TestGenWebSocketHandler.sendProgress(wsKey, msg);
    }

    /** 全局去重合并（需求 + 大纲 + 全量测试点，只删不补，在章节级基础上做跨章节最终收敛） */
    private void refineGlobal(Integer taskId, String wsKey, OutlineVO outline, String doc) {
        XMindNode root = getXMindData(taskId);
        if (root == null) return;
        List<Map<String, String>> points = collectPointsFull(root);
        if (points.size() < 2) return;

        TestGenWebSocketHandler.sendProgress(wsKey, "全局精修（跨章节去重合并），共 " + points.size() + " 个测试点...");
        Map<String, String> params = new HashMap<>();
        params.put("outline", outline == null ? "" : JSON.toJSONString(outline.getChapters()));
        params.put("points", JSON.toJSONString(points));
        params.put("doc", doc);

        String system = testGenPromptService.getSystemPrompt("refine_global_system");
        RefineBatch batch = runRefineCalls(taskId, List.of(params), system, "refine_global_user");

        // 调用失败(超时/网络/输出无法解析)时明确上报,避免与"确实无可合并"混淆成"去重/合并 0 条"
        if (batch.hasFailure()) {
            String msg = "全局精修未完成，已跳过：" + batch.firstError();
            log.warn("taskId={} {}", taskId, msg);
            TestGenWebSocketHandler.sendProgress(wsKey, msg);
            return;
        }

        Object lock = getTaskLock(taskId);
        int removed = 0;
        synchronized (lock) {
            XMindNode current = getXMindData(taskId);
            if (current == null) return;
            for (JSONObject res : batch.results) {
                if (res == null) continue;
                removed += applyDuplicateRemovals(current, res.getJSONArray("duplicateGroups"));
            }
            saveXMindData(taskId, current);
        }
        String msg = "全局精修完成：去重/合并 " + removed + " 条";
        log.info("taskId={} {}", taskId, msg);
        TestGenWebSocketHandler.sendProgress(wsKey, msg);
    }

    /** 一批精修调用的结果:results 与入参一一对应(失败为 null),errors 收集失败原因 */
    private static final class RefineBatch {
        final List<JSONObject> results = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        boolean hasFailure() { return !errors.isEmpty(); }
        String firstError() { return errors.isEmpty() ? "" : errors.get(0); }
    }

    /** 并发执行一批精修调用（每批一个 LLM 调用），返回解析结果与失败原因 */
    private RefineBatch runRefineCalls(Integer taskId, List<Map<String, String>> paramsList,
                                       String system, String userPromptKey) {
        RefineBatch batch = new RefineBatch();
        if (paramsList == null || paramsList.isEmpty()) return batch;
        int concurrency = Math.min(4, paramsList.size());
        ExecutorService pool = Executors.newFixedThreadPool(concurrency, r -> {
            Thread t = new Thread(r, "refine-" + taskId);
            t.setDaemon(true);
            return t;
        });
        try {
            List<CompletableFuture<JSONObject>> futures = new ArrayList<>();
            for (Map<String, String> params : paramsList) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    String resp = callLlmBlocking(system, PromptLoader.loadWithParams(userPromptKey, params));
                    JSONObject parsed = parseRefineResponse(resp);
                    if (parsed == null) {
                        // 调用成功但模型输出无法解析为预期 JSON —— 视为失败,连同原文抛出便于定位
                        throw new IllegalStateException("模型返回无法解析为预期JSON");
                    }
                    return parsed;
                }, pool));
            }
            for (CompletableFuture<JSONObject> f : futures) {
                try {
                    batch.results.add(f.join());
                } catch (Exception e) {
                    Throwable cause = (e instanceof java.util.concurrent.CompletionException && e.getCause() != null)
                            ? e.getCause() : e;
                    log.warn("taskId={} 精修调用失败", taskId, cause);
                    batch.results.add(null);
                    batch.errors.add(describeThrowable(cause));
                }
            }
        } finally {
            pool.shutdown();
        }
        return batch;
    }

    /** 收集所有 point 节点的 id/type/module/content 视图，给精修 agent 使用 */    private List<Map<String, String>> collectPointsFull(XMindNode root) {
        List<Map<String, String>> list = new ArrayList<>();
        collectPointsFullRecursive(root, root, list);
        return list;
    }

    private void collectPointsFullRecursive(XMindNode root, XMindNode node, List<Map<String, String>> list) {
        if (node == null) return;
        if ("point".equals(node.getType())) {
            String modulePath = buildModulePath(root, node);
            String[] parts = modulePath.split("-", 2);
            String category = parts.length > 0 ? parts[0] : "功能逻辑";
            String module = parts.length > 1 ? parts[1] : "";
            Map<String, String> m = new LinkedHashMap<>();
            m.put("id", node.getId());
            m.put("category", category);
            m.put("module", module);
            m.put("content", node.getTitle());
            list.add(m);
            return; // point 下不再递归
        }
        if (node.getChildren() != null) {
            for (XMindNode child : node.getChildren()) {
                collectPointsFullRecursive(root, child, list);
            }
        }
    }

    /** 收集 pointId -> chapterId 映射，供一级精修按章节分组（旁路，不进入下发大模型的载荷） */
    private void collectPointChapterIds(XMindNode node, Map<String, String> out) {
        if (node == null) return;
        if ("point".equals(node.getType())) {
            if (node.getChapterId() != null) out.put(node.getId(), node.getChapterId());
            return;
        }
        if (node.getChildren() != null) {
            for (XMindNode child : node.getChildren()) collectPointChapterIds(child, out);
        }
    }

    private JSONObject parseRefineResponse(String text) {
        if (text == null || text.isBlank()) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            log.warn("自动精修返回非法 JSON 对象：{}", text);
            return null;
        }
        try {
            return JSON.parseObject(text.substring(start, end + 1));
        } catch (Exception e) {
            log.warn("自动精修返回解析失败：{}: {}", text, e.toString());
            return null;
        }
    }

    private int applyDuplicateRemovals(XMindNode root, JSONArray groups) {
        if (groups == null || groups.isEmpty()) return 0;
        List<String> tmp = new ArrayList<>();
        collectPointIds(root, tmp);
        Set<String> existingIds = new HashSet<>(tmp);
        int removed = 0;
        for (int i = 0; i < groups.size(); i++) {
            JSONObject g = groups.getJSONObject(i);
            JSONArray toRemove = g.getJSONArray("removeIds");
            String keepId = g.getString("keepId");
            if (toRemove == null) continue;
            // 合并：keepId 作为锚点保留其 id/归属，把内容改写为合并后的表述（不新增节点）
            String mergedContent = g.getString("mergedContent");
            if (mergedContent != null && !mergedContent.isBlank()) {
                XMindNode keepNode = (keepId != null && existingIds.contains(keepId))
                        ? findNodeById(root, keepId) : null;
                // 合并缺少有效锚点时跳过本组，避免删掉原始点却丢失合并结果
                if (keepNode == null) continue;
                keepNode.setTitle(mergedContent.trim());
            }
            for (int j = 0; j < toRemove.size(); j++) {
                String rid = toRemove.getString(j);
                if (rid == null || rid.isBlank()) continue;
                if (rid.equals(keepId)) continue; // 防御：keep 与 remove 撞 id 时不删
                if (!existingIds.contains(rid)) continue;
                if (removeNodeById(root, rid)) {
                    removed++;
                    existingIds.remove(rid);
                }
            }
        }
        return removed;
    }

    private int applyAdditions(XMindNode root, JSONArray additions) {
        if (additions == null || additions.isEmpty()) return 0;
        // 收集允许的模块/子模块标题，用于校验 module 字段是否落在大纲内
        Set<String> allowedModuleTitles = new HashSet<>();
        collectModuleTitles(root, allowedModuleTitles);
        int added = 0;
        for (int i = 0; i < additions.size(); i++) {
            JSONObject a = additions.getJSONObject(i);
            String category = a.getString("category");
            String module = a.getString("module");
            String content = a.getString("content");
            // 分类维度由提示词自定义，此处只校验非空，不再限定固定枚举
            if (category == null || category.isBlank()) continue;
            if (module == null || module.isBlank()) continue;
            if (content == null || content.isBlank()) continue;
            // module 必须落在已存在的模块/子模块上
            String[] layers = module.split("-");
            String top = layers[0].trim();
            if (!allowedModuleTitles.contains(top)) {
                log.info("跳过越界漏点 module={}, content={}", module, content);
                continue;
            }
            JSONObject pj = new JSONObject();
            pj.put("category", category);
            pj.put("module", module);
            pj.put("content", content);
            addPointToTree(root, pj, null);
            added++;
        }
        return added;
    }

    private void collectModuleTitles(XMindNode node, Set<String> out) {
        if (node == null) return;
        if ("module".equals(node.getType())) out.add(node.getTitle());
        if (node.getChildren() != null) {
            for (XMindNode c : node.getChildren()) collectModuleTitles(c, out);
        }
    }

    /** 递归在树中按 id 删除节点；删除成功返回 true */
    private boolean removeNodeById(XMindNode node, String targetId) {
        if (node == null || node.getChildren() == null) return false;
        Iterator<XMindNode> it = node.getChildren().iterator();
        while (it.hasNext()) {
            XMindNode child = it.next();
            if (targetId.equals(child.getId())) {
                it.remove();
                return true;
            }
            if (removeNodeById(child, targetId)) return true;
        }
        return false;
    }

    /** 阻塞式调 LLM 取完整文本（精修等阶段使用） */
    private String callLlmBlocking(String system, String user) {
        return runStreamingToString(system, user, 900, 0.5);
    }

    /**
     * 用流式接口调 LLM 直到 onComplete，把所有 token 拼成最终字符串返回。
     * 复用 thinking 模型配置；timeoutSec / temperature 由调用方按场景指定。
     */
    private String runStreamingToString(String system, String user, int timeoutSec, double temperature) {
        ModelConfig cfg = aiModelService.getThinking();
        OpenAiStreamingChatModel streamingModel = OpenAiStreamingChatModel.builder()
                .apiKey(cfg.getApiKey())
                .baseUrl(cfg.getBaseUrl())
                .modelName(cfg.getModel())
                .timeout(Duration.ofSeconds(timeoutSec))
                .temperature(temperature)
                .build();

        StringBuilder buffer = new StringBuilder();
        CompletableFuture<Void> future = new CompletableFuture<>();
        streamingModel.generate(
                List.of(
                        dev.langchain4j.data.message.SystemMessage.from(system),
                        dev.langchain4j.data.message.UserMessage.from(user)
                ),
                new StreamingResponseHandler<>() {
                    @Override public void onNext(String token) { buffer.append(token); }
                    @Override public void onComplete(Response<AiMessage> response) { future.complete(null); }
                    @Override public void onError(Throwable error) { future.completeExceptionally(error); }
                }
        );
        try {
            future.get(timeoutSec, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("LLM 调用超时（>" + timeoutSec + "s，已接收 " + buffer.length() + " 字符）", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException("LLM 调用失败：" + describeThrowable(cause), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("LLM 调用被中断", e);
        }
        return repairJsonInnerQuotes(buffer.toString());
    }

    // ---- 用例生成（按模块批量 / 单点重试，流式） ----

    @Override
    @Async("taskExecutor")
    public void generateCasesForPoint(Integer taskId, String pointId) {
        generateCasesForPoints(taskId, List.of(pointId), testGenPromptService.getSystemPrompt("case_gen_system"));
    }

    private void generateCasesForChapter(Integer taskId, String chapterNodeId, String caseSystem) {
        Object lock = getTaskLock(taskId);
        List<String> pointIds = new ArrayList<>();
        synchronized (lock) {
            XMindNode root = getXMindData(taskId);
            if (root == null) return;
            XMindNode cn = findNodeById(root, chapterNodeId);
            if (cn == null) return;
            collectPointIds(cn, pointIds);
        }
        generateCasesForPoints(taskId, pointIds, caseSystem);
    }

    private void generateCasesForPoints(Integer taskId, List<String> pointIds, String caseSystem) {
        if (pointIds == null || pointIds.isEmpty()) return;
        String wsKey = String.valueOf(taskId);
        Object lock = getTaskLock(taskId);

        generatingPoints.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet()).addAll(pointIds);

        Map<String, String> refToId = new HashMap<>();
        JSONArray pointsPayload = new JSONArray();
        try {
            TestGenTaskPO task = taskMapper.selectById(taskId);

            synchronized (lock) {
                XMindNode root = getXMindData(taskId);
                if (root == null) return;
                int idx = 0;
                for (String pid : pointIds) {
                    XMindNode pn = findNodeById(root, pid);
                    if (pn == null) continue;
                    String ref = "p" + (++idx);
                    refToId.put(ref, pid);
                    String[] parts = buildModulePath(root, pn).split("-", 2);
                    JSONObject pp = new JSONObject();
                    pp.put("ref", ref);
                    pp.put("category", parts.length > 0 ? parts[0] : "功能逻辑");
                    pp.put("module", parts.length > 1 ? parts[1] : "");
                    pp.put("content", pn.getTitle());
                    pointsPayload.add(pp);
                    pn.setChildren(new ArrayList<>());
                    pn.setIcons(null);
                }
                saveXMindData(taskId, root);
            }
            if (refToId.isEmpty()) return;

            String docText = fetchDocText(taskId, task.getPrdName());
            String user = PromptLoader.loadWithParams("case_gen_user", Map.of(
                    "doc", truncateDocText(docText),
                    "points", JSON.toJSONString(pointsPayload)
            ));

            int maxAttempts = 2;
            Exception lastErr = null;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                int[] caseCount = new int[]{0};
                try {
                    callLlmStreaming(caseSystem, user, (jsonObj) -> {
                        try {
                            String ref = jsonObj.getString("pointRef");
                            String pid = ref != null ? refToId.get(ref) : null;
                            if (pid == null && refToId.size() == 1) pid = refToId.values().iterator().next();
                            if (pid == null) return;
                            XMindNode caseNode = buildSingleCaseNode(jsonObj);
                            if (caseNode == null) return;
                            synchronized (lock) {
                                XMindNode root = getXMindData(taskId);
                                XMindNode pn = findNodeById(root, pid);
                                if (pn != null) {
                                    pn.getChildren().add(caseNode);
                                    saveXMindData(taskId, root);
                                    caseCount[0]++;
                                    TestGenWebSocketHandler.sendPointCasesGenerated(wsKey, pn.getId(), pn.getChildren(), false);
                                }
                            }
                        } catch (Exception ex) {
                            log.warn("处理单个用例失败，跳过: {}", ex.toString());
                        }
                    });
                    if (caseCount[0] > 0) {
                        lastErr = null;
                        break;
                    }
                    lastErr = new RuntimeException("LLM 返回 0 条用例");
                    log.warn("模块用例第 {} 次生成 0 条，准备重试", attempt);
                } catch (Exception ex) {
                    lastErr = ex;
                    log.warn("模块用例第 {} 次生成失败：{}", attempt, ex.getMessage());
                }
                if (attempt < maxAttempts) {
                    synchronized (lock) {
                        XMindNode root = getXMindData(taskId);
                        if (root != null) {
                            for (String pid : refToId.values()) {
                                XMindNode pn = findNodeById(root, pid);
                                if (pn != null) pn.setChildren(new ArrayList<>());
                            }
                            saveXMindData(taskId, root);
                        }
                    }
                    try { Thread.sleep(1500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }

            boolean batchFailed = lastErr != null;
            synchronized (lock) {
                XMindNode root = getXMindData(taskId);
                if (root != null) {
                    for (String pid : refToId.values()) {
                        XMindNode pn = findNodeById(root, pid);
                        if (pn == null) continue;
                        boolean hasCases = pn.getChildren() != null && !pn.getChildren().isEmpty();
                        if (hasCases) {
                            pn.setIcons(null);
                            TestGenWebSocketHandler.sendPointCasesGenerated(wsKey, pn.getId(), new ArrayList<>(pn.getChildren()), true);
                        } else {
                            pn.setIcons(List.of("failed"));
                        }
                    }
                    saveXMindData(taskId, root);
                    TestGenWebSocketHandler.sendPointsGenerated(wsKey, root);
                }
            }
            if (batchFailed) {
                TestGenWebSocketHandler.sendError(wsKey, "用例生成失败：" + lastErr.getMessage());
            }
        } catch (Exception e) {
            log.error("用例生成失败, taskId={}: {}", taskId, e.toString());
            synchronized (lock) {
                XMindNode root = getXMindData(taskId);
                if (root != null) {
                    for (String pid : pointIds) {
                        XMindNode pn = findNodeById(root, pid);
                        if (pn != null && (pn.getChildren() == null || pn.getChildren().isEmpty())) {
                            pn.setIcons(List.of("failed"));
                        }
                    }
                    saveXMindData(taskId, root);
                    TestGenWebSocketHandler.sendPointsGenerated(wsKey, root);
                }
            }
            TestGenWebSocketHandler.sendError(wsKey, "用例生成失败: " + e.getMessage());
        } finally {
            Set<String> points = generatingPoints.get(taskId);
            if (points != null) {
                points.removeAll(pointIds);
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
        // 文件名加 taskId 后缀避免同需求文档不同任务相互覆盖/误删
        String fileName = buildRootTitle(taskId, taskPO.getPrdName()) + "_" + taskId.toString() + ".xmind";

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
        String outlineKey = String.format(REDIS_KEY_OUTLINE, taskId);
        redisTemplate.delete(List.of(xmindKey, chatKey, outlineKey));

        TestGenWebSocketHandler.closeAllSessions(taskId.toString());

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
                log.warn("删除旧 XMind 文件失败: {}: {}", oldTask.getXmindFileName(), e.toString());
            }
        }

        generatingTasks.remove(taskId);
        generatingPoints.remove(taskId);
        taskLocks.remove(taskId);
        String xmindKey = String.format(REDIS_KEY_XMIND, taskId);
        String chatKey = String.format(REDIS_KEY_CHAT, taskId);
        String outlineKey = String.format(REDIS_KEY_OUTLINE, taskId);
        redisTemplate.delete(List.of(xmindKey, chatKey, outlineKey));
        // 清理已解析文本缓存，重新生成时按最新的主文档/关联文档/二级文档开关重新解析整合
        clearParsedCache(taskId);

        TestGenTaskPO task = new TestGenTaskPO();
        task.setId(taskId);
        task.setStatus(TaskStatus.NEW.getCode());
        task.setMessage(null);
        task.setXmindFileName(null);
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(task);
        // 不调用 closeAllSessions：发起 regenerate 的用户 ws 仍需用于接收新一轮推送
    }

    // ---- 恢复状态 ----

    @Override
    public RestoreVO restoreTask(Integer taskId) {
        RestoreVO vo = new RestoreVO();
        TaskVO taskVO = getTask(taskId);
        OutlineVO outline = getOutline(taskId);

        // 兜底：任务停留在 PLAN_REVIEW 但 outline 已过期/丢失（Redis TTL 失效），
        // 回退到 NEW 状态，让前端可重新发起生成；否则用户会看到一个无任何面板的空白工作区
        if (taskVO != null
                && TaskStatus.PLAN_REVIEW.getCode().equals(taskVO.getStatus())
                && outline == null) {
            log.warn("任务 {} 停留在 PLAN_REVIEW 但 outline 缺失，回退到 NEW", taskId);
            updateStatus(taskId, TaskStatus.NEW.getCode(), "大纲已过期，请重新发起生成");
            taskVO.setStatus(TaskStatus.NEW.getCode());
            taskVO.setMessage("大纲已过期，请重新发起生成");
        }

        vo.setTask(taskVO);
        vo.setTreeData(getXMindData(taskId));
        Set<String> points = generatingPoints.get(taskId);
        vo.setGeneratingPointIds(points != null ? new ArrayList<>(points) : List.of());
        vo.setOutline(outline);
        return vo;
    }

    // ---- 删除任务 ----

    @Override
    public void deleteTask(Integer taskId) {
        TestGenTaskPO task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new RuntimeException("任务不存在");
        }

        // 删除 MinIO 中的 XMind 文件（不删除原始需求文档）
        if (task.getXmindFileName() != null) {
            try {
                minioUtil.deleteFile(task.getXmindFileName());
                log.info("已删除 XMind 文件: {}", task.getXmindFileName());
            } catch (Exception e) {
                log.warn("删除 XMind 文件失败: {}: {}", task.getXmindFileName(), e.toString());
            }
        }

        String xmindKey = String.format(REDIS_KEY_XMIND, taskId);
        String chatKey = String.format(REDIS_KEY_CHAT, taskId);
        String outlineKey = String.format(REDIS_KEY_OUTLINE, taskId);
        redisTemplate.delete(List.of(xmindKey, chatKey, outlineKey));
        log.info("已清理 Redis 缓存: taskId={}", taskId);
        clearParsedCache(taskId);

        generatingTasks.remove(taskId);
        generatingPoints.remove(taskId);
        taskLocks.remove(taskId);

        taskMapper.deleteById(taskId);
        log.info("已删除任务记录: taskId={}", taskId);

        TestGenWebSocketHandler.closeAllSessions(taskId.toString());
    }

    // ============ 内部工具方法 ============

    /** 提取 Throwable 的可读描述：Error（如 OOM/NoClassDefFound）往往无 message，需带上类名 */
    private String describeThrowable(Throwable e) {
        String msg = e.getMessage();
        if (msg != null && !msg.isBlank()) return msg;
        return e.getClass().getSimpleName();
    }

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

    /** 已解析+整合后的全量需求文本缓存对象名 */
    private String parsedObjectName(Integer taskId) {
        return "testgen_" + taskId + ".parsed.txt";
    }

    /** 清理任务的已解析文本缓存，确保下次生成会按最新的主文档/关联文档/开关重新解析整合 */
    private void clearParsedCache(Integer taskId) {
        try {
            minioUtil.deleteFile(parsedObjectName(taskId));
        } catch (Exception e) {
            log.warn("清理已解析文本缓存失败, taskId={}: {}", taskId, e.toString());
        }
    }

    private String fetchDocText(Integer taskId, String prdName, String wsKey) {
        // 已解析文本按任务维度缓存（prdName 可能是多个文件名以换行拼接，不适合直接作为对象名）
        String parsedFileName = parsedObjectName(taskId);
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
                String prdUrl = minioUtil.getUrl(name);
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
    private List<String> splitPrdNames(String prdName) {
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
        return runStreamingToString(system, user, 600, 0.3);
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
        ModelConfig cfg = aiModelService.getThinking();
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
            throw new RuntimeException(e.getMessage(), e);
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

            // 找到匹配的 }：跳过字符串内的花括号；对未转义的内部引号用结构符前瞻判断字符串边界
            int depth = 0;
            int braceEnd = -1;
            boolean inString = false;
            for (int i = braceStart; i < content.length(); i++) {
                char c = content.charAt(i);
                if (inString) {
                    if (c == '\\') {
                        i++;
                    } else if (c == '"' && isStructuralAfterQuote(content, i + 1)) {
                        inString = false;
                    }
                    continue;
                }
                if (c == '"') {
                    inString = true;
                } else if (c == '{') {
                    depth++;
                } else if (c == '}') {
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
                JSONObject obj = JSON.parseObject(repairJsonInnerQuotes(jsonStr));
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

    /**
     * 修复大模型输出里字符串值内部未转义的双引号（如 内容为 点击"确定"按钮 会破坏 JSON）。
     * 逐字符扫描：处于字符串内时遇到 "，若其后（跳过空白）不是结构符 : , } ] 或结尾，
     * 判定为内容引号并转义为 \"；否则视为字符串正常闭合。已转义的 \" 原样保留。
     */
    private static String repairJsonInnerQuotes(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder out = new StringBuilder(s.length() + 16);
        boolean inString = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!inString) {
                out.append(c);
                if (c == '"') inString = true;
                continue;
            }
            if (c == '\\') {
                out.append(c);
                if (i + 1 < s.length()) {
                    out.append(s.charAt(i + 1));
                    i++;
                }
                continue;
            }
            if (c == '"') {
                if (isStructuralAfterQuote(s, i + 1)) {
                    out.append(c);
                    inString = false;
                } else {
                    out.append("\\\"");
                }
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** 从 idx 起跳过空白后的首个字符是否为 JSON 结构符（: , } ]）或已到结尾 */
    private static boolean isStructuralAfterQuote(String s, int idx) {
        int i = idx;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        if (i >= s.length()) return true;
        char c = s.charAt(i);
        return c == ':' || c == ',' || c == '}' || c == ']';
    }

    /** 将单个测试点 JSON 添加到 XMind 树，返回新建 point 节点的 id（用于前端精确居中） */
    private String addPointToTree(XMindNode root, JSONObject pointJson, String chapterId) {
        String module = pointJson.getString("module");
        String category = pointJson.getString("category");
        String content = pointJson.getString("content");
        if (module == null || content == null) return null;

        // 清理流式输出带来的换行符
        module = module.replaceAll("[\\n\\r]+", " ").trim();
        content = content.replaceAll("[\\n\\r]+", " ").trim();
        if (category != null) category = category.replaceAll("[\\n\\r]+", " ").trim();

        // 1. 先找或创建分类节点（分类维度，默认测试类型：功能逻辑等）
        String typeLabel = category != null ? category : "功能逻辑";
        XMindNode typeNode = findChildByTitle(root, typeLabel);
        if (typeNode == null) {
            typeNode = newNode("module_" + UUID.randomUUID(), typeLabel, "module");
            root.getChildren().add(typeNode);
        }

        // 2. 在分类节点下创建模块层级
        String[] layers = module.split("-");
        String topModule = tidyTitle(layers[0]);
        String subModule = layers.length > 1 ? tidyTitle(layers[1]) : null;

        XMindNode moduleNode = findChildByTitle(typeNode, topModule);
        if (moduleNode == null) {
            moduleNode = newNode("module_" + UUID.randomUUID(), topModule, "module");
            typeNode.getChildren().add(moduleNode);
        }

        XMindNode targetNode = moduleNode;
        if (subModule != null && !subModule.isEmpty()) {
            XMindNode subNode = findChildByTitle(moduleNode, subModule);
            if (subNode == null) {
                subNode = newNode("module_" + UUID.randomUUID(), subModule, "module");
                moduleNode.getChildren().add(subNode);
            }
            targetNode = subNode;
        }

        // 3. 创建测试点节点
        XMindNode pointNode = newNode("point_" + UUID.randomUUID(), content, "point");
        pointNode.setChapterId(chapterId);
        targetNode.getChildren().add(pointNode);
        return pointNode.getId();
    }

    private void bindModuleToOutline(JSONObject pointJson, String moduleName) {
        String top = tidyTitle(moduleName);
        if (top.isEmpty()) return;
        String sub = extractSubModule(pointJson.getString("module"), top);
        pointJson.put("module", sub.isEmpty() ? top : top + "-" + sub);
    }

    private String extractSubModule(String raw, String moduleName) {
        if (raw == null) return "";
        for (String seg : raw.split("-")) {
            String s = tidyTitle(seg);
            if (s.isEmpty() || s.equals(moduleName)) continue;
            return capLen(s, 12);
        }
        return "";
    }

    private String tidyTitle(String s) {
        if (s == null) return "";
        String t = s.replaceAll("[\\r\\n]+", " ").replaceAll("\\s+", " ").trim();
        t = t.replaceAll("[\\-—:：、。,，；;.]+$", "");
        return t.trim();
    }

    private String capLen(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }


    /** 构建单个用例节点 */
    private XMindNode buildSingleCaseNode(JSONObject c) {
        String caseName = c.getString("用例名称");
        if (caseName == null) return null;

        String priority = c.getString("优先级");
        List<String> icons = null;
        if (priority != null && priority.startsWith("P")) {
            try {
                int level = Integer.parseInt(priority.substring(1));
                icons = List.of("priority-" + (level + 1));
            } catch (NumberFormatException e) {
                log.warn("无法解析优先级: {}", priority);
            }
        }
        XMindNode caseNode = newNode("case_" + UUID.randomUUID(), caseName, "case");
        caseNode.setIcons(icons);

        List<XMindNode> children = new ArrayList<>();
        String pre = c.getString("前置条件");
        if (pre != null && !pre.isBlank()) {
            children.add(newNode("step_" + UUID.randomUUID(), "前置条件:\n" + pre, "step"));
        }else{
            // 保证必有一个前置条件
            children.add(newNode("step_" + UUID.randomUUID(), "前置条件:", "step"));
        }
        JSONArray steps = c.getJSONArray("测试步骤");
        if (steps != null) {
            for (int j = 0; j < steps.size(); j++) {
                JSONObject step = steps.getJSONObject(j);
                XMindNode stepNode = newNode("step_" + UUID.randomUUID(), step.getString("执行操作"), "step");
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

    /** 自底向上剪除没有任何测试用例的空目录（module 节点，含只有测试点却没生成用例的情况），返回被剪除的目录数 */
    private int pruneEmptyModules(XMindNode node) {
        if (node == null || node.getChildren() == null || node.getChildren().isEmpty()) return 0;
        int removed = 0;
        for (XMindNode child : node.getChildren()) {
            removed += pruneEmptyModules(child);
        }
        List<XMindNode> kept = new ArrayList<>(node.getChildren().size());
        for (XMindNode child : node.getChildren()) {
            if ("module".equals(child.getType()) && !hasMeaningfulContent(child)) {
                removed++;
            } else {
                kept.add(child);
            }
        }
        node.setChildren(kept);
        return removed;
    }

    /** 子树中是否包含测试用例/自由节点等有意义的内容（仅有测试点、无用例的目录视为可清理） */
    private boolean hasMeaningfulContent(XMindNode node) {
        if (node == null) return false;
        String type = node.getType();
        if ("case".equals(type) || "free".equals(type)) return true;
        if (node.getChildren() != null) {
            for (XMindNode child : node.getChildren()) {
                if (hasMeaningfulContent(child)) return true;
            }
        }
        return false;
    }

    /** 统计带 failed icon 标记的 point 节点数 */
    private int countFailedPoints(XMindNode node) {
        if (node == null) return 0;
        int count = 0;
        if ("point".equals(node.getType()) && node.getIcons() != null && node.getIcons().contains("failed")) {
            count = 1;
        }
        if (node.getChildren() != null) {
            for (XMindNode child : node.getChildren()) {
                count += countFailedPoints(child);
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
     * 3. 按测试类型（功能逻辑/界面UI等）提升为根节点下第一级
     * 4. 保留所有模块节点（即使没有用例）
     * 导出结构：根 → 类型 → 模块路径 → 用例
     */
    private XMindNode rebuildForExport(XMindNode root) {
        // 树结构已经是：根节点 → 分类（功能逻辑/界面UI）→ 模块 → 测试点 → 用例
        // 导出时只需：过滤掉 free 节点和 point 节点，保留其他层级
        return filterForExport(root);
    }

    private XMindNode filterForExport(XMindNode node) {
        // 跳过自由节点
        if ("free".equals(node.getType())) {
            return null;
        }

        // 其他节点：递归过滤子节点
        XMindNode filtered = new XMindNode();
        filtered.setId(node.getId());
        filtered.setTitle(node.getTitle());
        filtered.setType(node.getType());
        filtered.setIcons(node.getIcons());
        filtered.setExpanded(node.getExpanded());

        if (node.getChildren() != null) {
            List<XMindNode> filteredChildren = new ArrayList<>();
            for (XMindNode child : node.getChildren()) {
                if ("free".equals(child.getType())) {
                    continue;
                }
                if ("point".equals(child.getType())) {
                    // 测试点节点：跳过自身，提取其用例子节点挂到当前层级
                    if (child.getChildren() != null) {
                        for (XMindNode caseNode : child.getChildren()) {
                            XMindNode filteredCase = filterForExport(caseNode);
                            if (filteredCase != null) {
                                filteredChildren.add(filteredCase);
                            }
                        }
                    }
                } else {
                    XMindNode filteredChild = filterForExport(child);
                    if (filteredChild != null) {
                        filteredChildren.add(filteredChild);
                    }
                }
            }
            filtered.setChildren(filteredChildren);
        }

        return filtered;
    }

    // ============ Prompt 模板 ============

    private String buildRootTitle(Integer taskId, String prdName) {
        // 只用主文档名称（有序标题的首个，主文档在前），不再拼接关联文档
        List<String> titles = taskId != null ? docTitles.get(taskId) : null;
        if (titles != null) {
            for (String t : titles) {
                if (t != null && !t.isBlank()) return t.trim();
            }
        }
        // 回退：从 prdName（主文档文件名或链接）推断
        List<String> names = splitPrdNames(prdName);
        if (names.isEmpty()) return "测试用例";
        String first = names.get(0);
        // 链接来源但无标题时，URL 不适合做标题，回退通用名
        if (first.startsWith("http://") || first.startsWith("https://")) {
            return "需求用例";
        }
        return stripExt(first);
    }
}
