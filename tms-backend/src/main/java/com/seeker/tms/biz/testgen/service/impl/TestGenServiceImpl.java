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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
        task.setCreator(dto.getCreator());
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
        } catch (Exception e) {
            log.error("生成大纲失败，taskId={}", taskId, e);
            updateStatus(taskId, TaskStatus.FAILED.getCode(), "失败：" + e.getMessage());
            TestGenWebSocketHandler.sendError(wsKey, e.getMessage());
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
        if (effective == null || effective.getModules() == null || effective.getModules().isEmpty()) {
            TestGenWebSocketHandler.sendError(wsKey, "大纲为空，请先生成或填写模块");
            // 还原状态，避免前端因预设 GENERATING 而卡在遮罩
            TestGenWebSocketHandler.sendTaskStatus(wsKey,
                    TaskStatus.PLAN_REVIEW.getCode(), "大纲为空，请先生成或填写模块");
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

            // 初始化根节点
            XMindNode root = newNode("root", buildRootTitle(task.getPrdName()), "root");
            generatingTasks.put(taskId, root);

            List<OutlineVO.ModuleNode> modules = effective.getModules();
            int total = modules.size();
            int failed = parallelExtractModules(wsKey, taskId, root, modules, summary, truncatedDoc);

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
            try {
                refinePoints(taskId, wsKey, effective, truncatedDoc);
            } catch (Exception ex) {
                log.warn("taskId={} 自动反思失败，跳过精修，直接进入用例生成阶段", taskId, ex);
                TestGenWebSocketHandler.sendProgress(wsKey, "自动精修失败，跳过：" + ex.getMessage());
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

            // 重新读取最终树
            XMindNode finalRoot = getXMindData(taskId);
            generatingTasks.remove(taskId);

            int caseCount = finalRoot != null ? countNodes(finalRoot, "case") : 0;
            int failedPoints = finalRoot != null ? countFailedPoints(finalRoot) : 0;
            String doneMsg = "用例生成完成，共 " + caseCount + " 条用例"
                    + (failedPoints > 0 ? "（" + failedPoints + " 个测试点失败，可右键单独重试）" : "");
            updateStatus(taskId, TaskStatus.EDITING.getCode(), doneMsg);
            TestGenWebSocketHandler.sendTaskStatus(wsKey, TaskStatus.EDITING.getCode(), doneMsg);
            TestGenWebSocketHandler.sendPointsGenerated(wsKey, finalRoot);
            TestGenWebSocketHandler.sendPhaseChanged(wsKey, "EDITING", "用例生成完成");
        } catch (Exception e) {
            log.error("提取测试点失败，taskId={}", taskId, e);
            generatingTasks.remove(taskId);
            updateStatus(taskId, TaskStatus.FAILED.getCode(), "失败：" + e.getMessage());
            TestGenWebSocketHandler.sendError(wsKey, e.getMessage());
        }
    }

    /**
     * 并发为当前任务下所有 point 节点生成用例，完成后自动折叠。
     * 失败的 point 节点保留并标记 failed，由用户手动重试。
     */
    private void autoGenerateCasesForAllPoints(Integer taskId) {
        XMindNode root = getXMindData(taskId);
        if (root == null) return;
        List<String> pointIds = new ArrayList<>();
        collectPointIds(root, pointIds);
        if (pointIds.isEmpty()) return;

        String wsKey = String.valueOf(taskId);
        int total = pointIds.size();
        TestGenWebSocketHandler.sendProgress(wsKey, "开始并发生成用例，共 " + total + " 个测试点...");

        int concurrency = Math.min(4, Math.max(1, total));
        ExecutorService pool = Executors.newFixedThreadPool(concurrency, r -> {
            Thread t = new Thread(r, "case-gen-" + taskId);
            t.setDaemon(true);
            return t;
        });
        AtomicInteger done = new AtomicInteger(0);
        AtomicInteger ok = new AtomicInteger(0);
        AtomicInteger fail = new AtomicInteger(0);
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>(total);
            for (String pid : pointIds) {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        generateCasesForPointInternal(taskId, pid);
                        ok.incrementAndGet();
                    } catch (Exception ex) {
                        fail.incrementAndGet();
                        log.warn("用例生成任务异常 taskId={} pointId={}", taskId, pid, ex);
                    } finally {
                        int finished = done.incrementAndGet();
                        TestGenWebSocketHandler.sendProgress(wsKey,
                                "已完成 " + finished + "/" + total + " 个测试点的用例生成");
                    }
                }, pool));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            TestGenWebSocketHandler.sendProgress(wsKey,
                    "用例生成阶段完成：成功 " + ok.get() + " 个，失败 " + fail.get() + " 个"
                            + (fail.get() > 0 ? "（失败的测试点已标记，可在面板上右键单独重试）" : ""));
        } finally {
            pool.shutdown();
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
     * 并发为大纲中的每个模块抽取测试点；写树通过 taskLock 保证线程安全。
     * 返回失败模块数。
     */
    private int parallelExtractModules(String wsKey, Integer taskId, XMindNode root,
                                       List<OutlineVO.ModuleNode> modules,
                                       String summary, String docText) {
        // 过滤空模块名，并保留原索引用于进度文案
        List<OutlineVO.ModuleNode> effective = new ArrayList<>();
        for (OutlineVO.ModuleNode m : modules) {
            String n = m.getName() == null ? "" : m.getName().trim();
            if (!n.isEmpty()) effective.add(m);
        }
        int total = effective.size();
        if (total == 0) return 0;

        TestGenWebSocketHandler.sendProgress(wsKey, "开始并发提取测试点，共 " + total + " 个章节...");

        int concurrency = Math.min(4, Math.max(1, total));
        ExecutorService pool = Executors.newFixedThreadPool(concurrency, r -> {
            Thread t = new Thread(r, "module-extract-" + taskId);
            t.setDaemon(true);
            return t;
        });
        AtomicInteger done = new AtomicInteger(0);
        AtomicInteger failedCnt = new AtomicInteger(0);
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>(total);
            for (OutlineVO.ModuleNode m : effective) {
                futures.add(CompletableFuture.runAsync(() -> {
                    String moduleName = m.getName().trim();
                    try {
                        extractModulePoints(wsKey, taskId, root, m, summary, docText);
                    } catch (Exception ex) {
                        failedCnt.incrementAndGet();
                        log.warn("章节 [{}] 提取失败，继续其他模块", moduleName, ex);
                        TestGenWebSocketHandler.sendProgress(wsKey,
                                "模块 " + moduleName + " 提取失败：" + ex.getMessage());
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

    /** 单模块提取：流式调用，每解析出一个测试点立即挂树并推送 */
    private void extractModulePoints(String wsKey, Integer taskId, XMindNode root,
                                     OutlineVO.ModuleNode module,
                                     String summary, String docText) {
        String moduleName = module.getName() == null ? "" : module.getName().trim();
        String moduleScope = module.getScope() == null ? "" : module.getScope();

        Map<String, String> params = new HashMap<>();
        params.put("moduleName", moduleName);
        params.put("moduleScope", moduleScope);
        params.put("summary", summary);
        params.put("doc", docText);

        Object lock = getTaskLock(taskId);
        callLlmStreaming(
                PromptLoader.load("extract_module_system"),
                PromptLoader.loadWithParams("extract_module_user", params),
                (jsonObj) -> {
                    try {
                        synchronized (lock) {
                            String newPointId = addPointToTree(root, jsonObj);
                            TestGenWebSocketHandler.sendPointAdded(wsKey, root, newPointId);
                        }
                    } catch (Exception ex) {
                        log.warn("处理章节[{}]单个测试点失败，跳过", moduleName, ex);
                    }
                }
        );
    }

    /**
     * 调用规划 Agent，期望返回单个 OutlineVO 形态的 JSON 对象。
     */
    private OutlineVO callPlanningAgent(String docText) {
        String system = PromptLoader.load("planning_system");
        String user = PromptLoader.loadWithParams("planning_user", Map.of("doc", docText));
        String response = runStreamingToString(system, user, 300, 0.5);
        return parseOutlineResponse(response);
    }

    /** 容错地从模型回复中提取首个完整 JSON 对象并解析为 OutlineVO */
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

    // ---- 自动精修测试点（去重 + 补漏） ----

    /**
     * 测试点提取完成后、自动生成用例之前调用一次。
     * 让 LLM 比对当前测试点清单与原始文档，剔除重复并补齐漏点。
     * 补出来的"漏点"必须严格满足测试点提取协议（type/module/content），
     * 直接复用 addPointToTree 落树。失败仅记日志，不阻断主流程。
     */
    private void refinePoints(Integer taskId, String wsKey,
                              OutlineVO outline, String truncatedDoc) {
        XMindNode root = getXMindData(taskId);
        if (root == null) return;

        List<Map<String, String>> points = collectPointsFull(root);
        if (points.isEmpty()) {
            log.info("taskId={} 现有测试点为空，跳过自动精修", taskId);
            return;
        }

        TestGenWebSocketHandler.sendPhaseChanged(wsKey, "REFINING", "AI 正在精修测试点（去重 + 补漏）...");
        TestGenWebSocketHandler.sendProgress(wsKey, "AI 正在精修测试点（去重 + 补漏）...");

        String summary = (outline != null && outline.getSummary() != null) ? outline.getSummary() : "";
        Map<String, String> params = new HashMap<>();
        params.put("summary", summary);
        params.put("points", JSON.toJSONString(points));
        params.put("doc", truncatedDoc);

        String response = callLlmBlocking(
                PromptLoader.load("refine_points_system"),
                PromptLoader.loadWithParams("refine_points_user", params));
        JSONObject result = parseRefineResponse(response);
        if (result == null) {
            log.warn("taskId={} 自动精修返回解析失败，跳过", taskId);
            return;
        }

        Object lock = getTaskLock(taskId);
        synchronized (lock) {
            XMindNode current = getXMindData(taskId);
            if (current == null) return;
            int removed = applyDuplicateRemovals(current, result.getJSONArray("duplicateGroups"));
            int added = applyAdditions(current, result.getJSONArray("additions"));
            saveXMindData(taskId, current);
            String msg = "测试点精修：去重 " + removed + " 条，补齐 " + added + " 条";
            log.info("taskId={} {}", taskId, msg);
            TestGenWebSocketHandler.sendProgress(wsKey, msg);
        }
    }

    /** 收集所有 point 节点的 id/type/module/content 视图，给精修 agent 使用 */
    private List<Map<String, String>> collectPointsFull(XMindNode root) {
        List<Map<String, String>> list = new ArrayList<>();
        collectPointsFullRecursive(root, root, list);
        return list;
    }

    private void collectPointsFullRecursive(XMindNode root, XMindNode node, List<Map<String, String>> list) {
        if (node == null) return;
        if ("point".equals(node.getType())) {
            String modulePath = buildModulePath(root, node);
            String[] parts = modulePath.split("-", 2);
            String type = parts.length > 0 ? parts[0] : "功能逻辑";
            String module = parts.length > 1 ? parts[1] : "";
            Map<String, String> m = new LinkedHashMap<>();
            m.put("id", node.getId());
            m.put("type", type);
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
            log.warn("自动精修返回解析失败：{}", text, e);
            return null;
        }
    }

    private int applyDuplicateRemovals(XMindNode root, JSONArray groups) {
        if (groups == null || groups.isEmpty()) return 0;
        Set<String> existingIds = new HashSet<>();
        List<String> tmp = new ArrayList<>();
        collectPointIds(root, tmp);
        existingIds.addAll(tmp);
        int removed = 0;
        for (int i = 0; i < groups.size(); i++) {
            JSONObject g = groups.getJSONObject(i);
            JSONArray toRemove = g.getJSONArray("removeIds");
            String keepId = g.getString("keepId");
            if (toRemove == null) continue;
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
        Set<String> allowedTypes = Set.of("功能逻辑", "美术效果", "配置管理", "数据埋点", "异常边界");
        int added = 0;
        for (int i = 0; i < additions.size(); i++) {
            JSONObject a = additions.getJSONObject(i);
            String type = a.getString("type");
            String module = a.getString("module");
            String content = a.getString("content");
            if (type == null || !allowedTypes.contains(type)) continue;
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
            pj.put("type", type);
            pj.put("module", module);
            pj.put("content", content);
            addPointToTree(root, pj);
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
        return runStreamingToString(system, user, 420, 0.5);
    }

    /**
     * 用流式接口调 LLM 直到 onComplete，把所有 token 拼成最终字符串返回。
     * 复用 thinking 模型配置；timeoutSec / temperature 由调用方按场景指定。
     */
    private String runStreamingToString(String system, String user, int timeoutSec, double temperature) {
        LlmProperties.ModelConfig cfg = llmProperties.getThinking();
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
        } catch (Exception e) {
            throw new RuntimeException("LLM 调用失败", e);
        }
        return buffer.toString();
    }

    // ---- 单测试点生成用例（流式） ----

    @Override
    @Async("taskExecutor")
    public void generateCasesForPoint(Integer taskId, String pointId) {
        generateCasesForPointInternal(taskId, pointId);
    }

    /**
     * 单个测试点生成用例的核心实现：拉用例 → 完成时 case 作为 point 子节点保留。
     * 失败则在 point 上打 failed 图标，便于用户右键重试。
     * 自动批量接力与用户手动触发都复用此方法（前者通过 autoGenerateCasesForAllPoints
     * 直接同步调用以拿到完成时机，后者通过 @Async 包装的 generateCasesForPoint 异步调）。
     */
    private void generateCasesForPointInternal(Integer taskId, String pointId) {
        String wsKey = String.valueOf(taskId);
        Object lock = getTaskLock(taskId);
        boolean success = false;

        // 注册生成中状态
        generatingPoints.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet()).add(pointId);

        try {
            TestGenTaskPO task = taskMapper.selectById(taskId);

            // 在锁内读取树并清空该测试点的子节点（兼容重试场景），同时清掉历史 failed 标记
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
                pointNode.setIcons(null);
                saveXMindData(taskId, root);
            }

            String docText = fetchDocText(taskId, task.getPrdName());
            String pointTitle = pointNode.getTitle();

            // buildModulePath 需要 root，在锁内获取
            String modulePath;
            synchronized (lock) {
                XMindNode root = getXMindData(taskId);
                modulePath = buildModulePath(root, pointNode);
            }
            String[] pathParts = modulePath.split("-", 2);
            String type = pathParts.length > 0 ? pathParts[0] : "功能逻辑";
            String module = pathParts.length > 1 ? pathParts[1] : "";

            JSONObject pointPayload = new JSONObject();
            pointPayload.put("type", type);
            pointPayload.put("module", module);
            pointPayload.put("content", pointTitle);

            String system = buildCaseGenSystem();
            String user = PromptLoader.loadWithParams("case_gen_user", Map.of(
                    "doc", truncateDocText(docText),
                    "points", JSON.toJSONString(pointPayload)
            ));

            // 失败重试 1 次：限流/瞬态网络抖动场景能自愈；流式调用结束但收 0 case 也算失败重试
            int maxAttempts = 2;
            Exception lastErr = null;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                int[] caseCount = new int[]{0};
                try {
                    callLlmStreaming(system, user, (jsonObj) -> {
                        try {
                            XMindNode caseNode = buildSingleCaseNode(jsonObj);
                            if (caseNode != null) {
                                synchronized (lock) {
                                    XMindNode root = getXMindData(taskId);
                                    XMindNode pn = findNodeById(root, pointId);
                                    if (pn != null) {
                                        pn.getChildren().add(caseNode);
                                        saveXMindData(taskId, root);
                                        caseCount[0]++;
                                        TestGenWebSocketHandler.sendPointCasesGenerated(wsKey, pointId, pn.getChildren(), false);
                                    }
                                }
                            }
                        } catch (Exception ex) {
                            log.warn("处理单个用例失败，跳过", ex);
                        }
                    });
                    if (caseCount[0] > 0) {
                        lastErr = null;
                        break; // 成功
                    }
                    lastErr = new RuntimeException("LLM 返回 0 条用例");
                    log.warn("测试点 {} 第 {} 次生成 0 条用例，准备重试", pointId, attempt);
                } catch (Exception ex) {
                    lastErr = ex;
                    log.warn("测试点 {} 第 {} 次生成失败：{}", pointId, attempt, ex.getMessage());
                }
                if (attempt < maxAttempts) {
                    // 重试前清空已写入的部分用例，避免叠加
                    synchronized (lock) {
                        XMindNode root = getXMindData(taskId);
                        XMindNode pn = root != null ? findNodeById(root, pointId) : null;
                        if (pn != null) {
                            pn.setChildren(new ArrayList<>());
                            saveXMindData(taskId, root);
                        }
                    }
                    try { Thread.sleep(1500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
            if (lastErr != null) {
                throw new RuntimeException("用例生成最终失败：" + lastErr.getMessage(), lastErr);
            }

            // 生成完成 → 保留 point 节点，case 作为其子节点（保持简单模型）
            synchronized (lock) {
                XMindNode root = getXMindData(taskId);
                if (root != null) {
                    XMindNode pn = findNodeById(root, pointId);
                    List<XMindNode> finalCases = (pn != null && pn.getChildren() != null)
                            ? new ArrayList<>(pn.getChildren()) : List.of();
                    saveXMindData(taskId, root);
                    TestGenWebSocketHandler.sendPointCasesGenerated(wsKey, pointId, finalCases, true);
                }
            }
            success = true;
        } catch (Exception e) {
            log.error("单测试点生成用例失败, taskId={}, pointId={}", taskId, pointId, e);
            // 失败：保留 point 节点 + 标 failed 图标，让用户能识别并重试
            synchronized (lock) {
                XMindNode root = getXMindData(taskId);
                if (root != null) {
                    XMindNode pn = findNodeById(root, pointId);
                    if (pn != null) {
                        pn.setIcons(List.of("failed"));
                        saveXMindData(taskId, root);
                        TestGenWebSocketHandler.sendPointsGenerated(wsKey, root);
                    }
                }
            }
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
        // 文件名加 taskId 后缀避免同需求文档不同任务相互覆盖/误删
        String fileName = buildRootTitle(taskPO.getPrdName()) + "_" + taskId.toString() + ".xmind";

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
                log.warn("删除旧 XMind 文件失败: {}", oldTask.getXmindFileName(), e);
            }
        }

        generatingTasks.remove(taskId);
        generatingPoints.remove(taskId);
        taskLocks.remove(taskId);
        String xmindKey = String.format(REDIS_KEY_XMIND, taskId);
        String chatKey = String.format(REDIS_KEY_CHAT, taskId);
        String outlineKey = String.format(REDIS_KEY_OUTLINE, taskId);
        redisTemplate.delete(List.of(xmindKey, chatKey, outlineKey));

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
        vo.setChatHistory(agentChatService.getChatHistory(taskId));
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

        // 1. 删除 MinIO 中的 XMind 文件（不删除原始需求文档）
        if (task.getXmindFileName() != null) {
            try {
                minioUtil.deleteFile(task.getXmindFileName());
                log.info("已删除 XMind 文件: {}", task.getXmindFileName());
            } catch (Exception e) {
                log.warn("删除 XMind 文件失败: {}", task.getXmindFileName(), e);
            }
        }

        // 2. 清理 Redis 缓存
        String xmindKey = String.format(REDIS_KEY_XMIND, taskId);
        String chatKey = String.format(REDIS_KEY_CHAT, taskId);
        String outlineKey = String.format(REDIS_KEY_OUTLINE, taskId);
        redisTemplate.delete(List.of(xmindKey, chatKey, outlineKey));
        log.info("已清理 Redis 缓存: taskId={}", taskId);

        // 3. 清理内存缓存
        generatingTasks.remove(taskId);
        generatingPoints.remove(taskId);
        taskLocks.remove(taskId);

        // 4. 删除 MySQL 记录
        taskMapper.deleteById(taskId);
        log.info("已删除任务记录: taskId={}", taskId);

        TestGenWebSocketHandler.closeAllSessions(taskId.toString());
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

    /** 将单个测试点 JSON 添加到 XMind 树，返回新建 point 节点的 id（用于前端精确居中） */
    private String addPointToTree(XMindNode root, JSONObject pointJson) {
        String module = pointJson.getString("module");
        String type = pointJson.getString("type");
        String content = pointJson.getString("content");
        if (module == null || content == null) return null;

        // 清理流式输出带来的换行符
        module = module.replaceAll("[\\n\\r]+", " ").trim();
        content = content.replaceAll("[\\n\\r]+", " ").trim();
        if (type != null) type = type.replaceAll("[\\n\\r]+", " ").trim();

        // 1. 先找或创建分类节点（功能逻辑、界面UI等）
        String typeLabel = type != null ? type : "功能逻辑";
        XMindNode typeNode = findChildByTitle(root, typeLabel);
        if (typeNode == null) {
            typeNode = newNode("module_" + UUID.randomUUID(), typeLabel, "module");
            root.getChildren().add(typeNode);
        }

        // 2. 在分类节点下创建模块层级
        String[] layers = module.split("-");
        String topModule = layers[0].trim();
        String subModule = layers.length > 1 ? layers[1].trim() : null;

        XMindNode moduleNode = findChildByTitle(typeNode, topModule);
        if (moduleNode == null) {
            moduleNode = newNode("module_" + UUID.randomUUID(), topModule, "module");
            typeNode.getChildren().add(moduleNode);
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

        // 3. 创建测试点节点
        XMindNode pointNode = newNode("point_" + UUID.randomUUID(), content, "point");
        targetNode.getChildren().add(pointNode);
        return pointNode.getId();
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

    private String buildRootTitle(String prdName) {
        // 从文件名中提取标题（去掉扩展名）
        if (prdName == null || prdName.isBlank()) return "测试用例";
        int dotIndex = prdName.lastIndexOf('.');
        return dotIndex > 0 ? prdName.substring(0, dotIndex) : prdName;
    }

    private String buildCaseGenSystem() {
        return PromptLoader.load("case_gen_system");
    }
}
