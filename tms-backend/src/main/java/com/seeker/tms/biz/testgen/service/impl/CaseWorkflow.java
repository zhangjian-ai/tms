package com.seeker.tms.biz.testgen.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.seeker.tms.biz.testgen.entities.OutlineVO;
import com.seeker.tms.biz.testgen.entities.TestGenTaskPO;
import com.seeker.tms.biz.testgen.entities.XMindNode;
import com.seeker.tms.biz.testgen.mapper.TestGenTaskMapper;
import com.seeker.tms.biz.testgen.model.ModelConfig;
import com.seeker.tms.biz.testgen.service.AiModelService;
import com.seeker.tms.biz.testgen.service.TestGenPromptService;
import com.seeker.tms.biz.testgen.utils.PromptLoader;
import com.seeker.tms.biz.testgen.utils.XMindTrees;
import com.seeker.tms.biz.testgen.websocket.TestGenWebSocketHandler;
import com.seeker.tms.common.llm.LlmClient;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 用例生成流水线：规划（章节大纲）、按章节并发生成用例、目录节点手动补充生成，以及两级精修
 * （章节内去重·合并·补漏 → 全局去重合并）。树的读写与锁经 {@link TestGenStore} 共享。
 */
@Slf4j
@Component
@AllArgsConstructor
public class CaseWorkflow {

    private final TestGenStore store;
    private final RequirementDocService docService;
    private final LlmClient llmClient;
    private final AiModelService aiModelService;
    private final TestGenPromptService testGenPromptService;
    private final TestGenTaskMapper taskMapper;

    // ---- 规划 ----

    /** 调用规划 Agent，返回单个 OutlineVO 形态的章节大纲。 */
    public OutlineVO plan(String docText) {
        String system = testGenPromptService.getSystemPrompt("planning_system");
        String user = PromptLoader.loadWithParams("planning_user", Map.of("doc", docText));
        String response = llmClient.streamToString(conn(), system, user, 600, 0.5);
        return parseOutlineResponse(response);
    }

    private OutlineVO parseOutlineResponse(String text) {
        if (text == null || text.isBlank()) {
            throw new RuntimeException("规划返回为空");
        }
        String json = LlmClient.extractFirstJsonObject(text);
        if (json == null) {
            throw new RuntimeException("规划返回不是合法 JSON：" + text);
        }
        try {
            return JSON.parseObject(json, OutlineVO.class);
        } catch (Exception e) {
            throw new RuntimeException("规划返回解析失败：" + json, e);
        }
    }

    // ---- 生成主流程 ----

    /**
     * 确认大纲后的用例生成主流程：预建分类目录 → 按章节并发生成 → 两级精修 → 清理空目录。
     * 返回 {caseCount, failedChapters}。精修为尽力而为，其失败不影响已生成用例。
     */
    public int[] runGeneration(Integer taskId, String prdName, OutlineVO outline, String docText) {
        String wsKey = String.valueOf(taskId);
        String truncatedDoc = docService.truncateDocText(docText);
        List<OutlineVO.Chapter> chapters = outline.getChapters();

        // 预建分类目录（顶层，顺序固定）；章节模块在生成时按需挂到对应分类下。
        // 结构：root → 分类(module,chapterId=null) → 章节(module,chapterId) → 用例(case)
        XMindNode root = XMindTrees.newNode("root", docService.buildRootTitle(taskId, prdName), "root");
        for (String cat : XMindTrees.CATEGORY_ORDER) {
            root.getChildren().add(XMindTrees.newNode("module_" + UUID.randomUUID(), cat, "module"));
        }
        store.startGenerating(taskId, root);
        TestGenWebSocketHandler.sendTreeUpdated(wsKey, root);

        // 阶段 A：按章节并发生成用例
        generateAllChapters(taskId, chapters, truncatedDoc);
        log.info("taskId={} 用例生成阶段完成", taskId);

        // 阶段 B：章节内精修 → 全局精修（精修失败不影响已生成用例）
        try {
            refine(taskId, wsKey, outline, truncatedDoc);
        } catch (Throwable ex) {
            log.warn("taskId={} 用例精修失败，跳过: {}", taskId, ex.toString());
            TestGenWebSocketHandler.sendProgress(wsKey, "用例精修失败，跳过：" + describeThrowable(ex));
        }

        XMindNode finalRoot = store.getTree(taskId);
        store.endGenerating(taskId);

        // 精修后可能残留没有任何用例的空目录，导出前统一清理（保留标记 failed 的章节供重试）
        if (finalRoot != null) {
            int prunedDirs = XMindTrees.pruneEmptyModules(finalRoot);
            if (prunedDirs > 0) {
                store.saveTree(taskId, finalRoot);
                log.info("taskId={} 清理空目录 {} 个", taskId, prunedDirs);
            }
        }

        int caseCount = finalRoot != null ? XMindTrees.countNodes(finalRoot, "case") : 0;
        int failedChapters = finalRoot != null ? XMindTrees.countFailedChapters(finalRoot) : 0;
        return new int[]{caseCount, failedChapters};
    }

    /** 并发为大纲中每个章节生成用例，用例按 分类→章节 挂到对应目录下 */
    private void generateAllChapters(Integer taskId, List<OutlineVO.Chapter> chapters, String truncatedDoc) {
        if (chapters == null || chapters.isEmpty()) return;
        String wsKey = String.valueOf(taskId);
        int total = chapters.size();
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
            for (OutlineVO.Chapter c : chapters) {
                if (c == null || c.getName() == null) continue;
                String chapterName = XMindTrees.tidyTitle(c.getName());
                String chapterId = c.getId();
                String chapterScope = c.getScope() == null ? "" : c.getScope();
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        streamChapter(taskId, chapterId, chapterName, chapterScope, caseSystem, truncatedDoc);
                    } catch (Exception ex) {
                        log.warn("章节用例生成异常 taskId={} chapter={}: {}", taskId, chapterName, ex.toString());
                    } finally {
                        int finished = done.incrementAndGet();
                        TestGenWebSocketHandler.sendProgress(wsKey,
                                "已完成 " + finished + "/" + total + " 个章节的用例生成");
                    }
                }, pool));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            int failedChapters = XMindTrees.countFailedChapters(store.getTree(taskId));
            TestGenWebSocketHandler.sendProgress(wsKey,
                    "用例生成阶段完成"
                            + (failedChapters > 0 ? "：" + failedChapters + " 个章节失败，可在面板上右键单独重试" : ""));
        } finally {
            pool.shutdown();
        }
    }

    /**
     * 为单个章节流式生成用例，按用例自带的 category 挂到 root→分类→章节 下。
     * 每写入一条用例，推送其所在“分类目录”的最新子树（分类节点已预建，前端可增量更新）。
     * 两次尝试仍为 0 条时，在默认分类下建带 failed 标记的章节占位节点，供右键单独重试。
     */
    private void streamChapter(Integer taskId, String chapterId, String chapterName,
                               String chapterScope, String caseSystem, String truncatedDoc) {
        String wsKey = String.valueOf(taskId);
        Object lock = store.getLock(taskId);

        Map<String, String> params = new HashMap<>();
        params.put("chapterName", chapterName);
        params.put("chapterScope", chapterScope);
        params.put("doc", truncatedDoc);
        String user = PromptLoader.loadWithParams("case_gen_user", params);

        int maxAttempts = 2;
        Exception lastErr = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            List<String> addedThisAttempt = new ArrayList<>();
            int[] caseCount = new int[]{0};
            try {
                llmClient.streamJsonObjects(conn(), caseSystem, user, 180, 0.7, (jsonObj) -> {
                    try {
                        XMindNode caseNode = XMindTrees.buildSingleCaseNode(jsonObj);
                        if (caseNode == null) return;
                        synchronized (lock) {
                            XMindNode root = store.getTree(taskId);
                            if (root == null) return;
                            XMindNode categoryNode = XMindTrees.placeCaseByCategoryChapter(
                                    root, chapterName, chapterId, jsonObj.getString("category"), caseNode);
                            store.saveTree(taskId, root);
                            caseCount[0]++;
                            addedThisAttempt.add(caseNode.getId());
                            // 推送该分类目录最新子树（分类节点预建、前端已知，可增量替换）
                            TestGenWebSocketHandler.sendNodeCasesGenerated(wsKey, categoryNode.getId(),
                                    new ArrayList<>(categoryNode.getChildren()), false);
                        }
                    } catch (Exception ex) {
                        log.warn("处理单个用例失败，跳过: {}", ex.toString());
                    }
                });
                if (caseCount[0] > 0) { lastErr = null; break; }
                lastErr = new RuntimeException("LLM 返回 0 条用例");
                log.warn("章节[{}]用例第 {} 次生成 0 条，准备重试", chapterName, attempt);
            } catch (Exception ex) {
                lastErr = ex;
                log.warn("章节[{}]用例第 {} 次生成失败：{}", chapterName, attempt, ex.getMessage());
            }
            // 重试前移除本次已写入的用例，避免与下一次结果叠加
            if (lastErr != null && attempt < maxAttempts) {
                synchronized (lock) {
                    XMindNode root = store.getTree(taskId);
                    if (root != null) {
                        for (String cid : addedThisAttempt) XMindTrees.removeNodeById(root, cid);
                        store.saveTree(taskId, root);
                    }
                }
                try { Thread.sleep(1500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }

        // 整章失败（0 用例）：在默认分类下建 failed 章节占位节点，作为右键重试入口
        if (lastErr != null) {
            synchronized (lock) {
                XMindNode root = store.getTree(taskId);
                if (root != null) {
                    XMindNode categoryNode = XMindTrees.findOrCreateCategory(root, XMindTrees.DEFAULT_CATEGORY);
                    XMindNode chapterNode = XMindTrees.findOrCreateChapter(categoryNode, chapterName, chapterId);
                    chapterNode.setIcons(List.of("failed"));
                    store.saveTree(taskId, root);
                    TestGenWebSocketHandler.sendNodeCasesGenerated(wsKey, categoryNode.getId(),
                            new ArrayList<>(categoryNode.getChildren()), false);
                }
            }
            TestGenWebSocketHandler.sendProgress(wsKey, "章节[" + chapterName + "]生成失败：" + lastErr.getMessage());
        }
    }

    // ---- 目录节点手动补充生成 ----

    /**
     * 目录节点手动生成用例：基于需求文档 + 用户补充测试内容直接生成，仅追加到该节点下，不走精修、不清空已有用例。
     */
    public void generateForNode(Integer taskId, String nodeId, String extraRequirement) {
        String wsKey = String.valueOf(taskId);
        // 补充测试内容为空时不生成（前端已必填，此处兜底防御）
        if (extraRequirement == null || extraRequirement.isBlank()) {
            TestGenWebSocketHandler.sendError(wsKey, "请填写补充测试内容后再生成");
            return;
        }
        XMindNode root = store.getTree(taskId);
        if (root == null) { TestGenWebSocketHandler.sendError(wsKey, "暂无数据"); return; }
        XMindNode target = XMindTrees.findNodeById(root, nodeId);
        if (target == null) { TestGenWebSocketHandler.sendError(wsKey, "目标节点不存在"); return; }

        // 依据节点在树中的位置推断 分类(category) 与 章节(chapterName)：
        // - 目标挂在 root 下 → 目标本身即分类目录（无具体章节）
        // - 目标挂在某分类下 → 父级即分类，目标即章节目录
        XMindNode parent = XMindTrees.findParent(root, nodeId);
        String category = "";
        String chapterName = "";
        if (parent == null || "root".equals(parent.getType())) {
            category = XMindTrees.tidyTitle(target.getTitle());
        } else {
            category = XMindTrees.tidyTitle(parent.getTitle());
            chapterName = XMindTrees.tidyTitle(target.getTitle());
        }

        // 章节范围（scope）取自大纲，供提示词圈定生成范围
        String chapterScope = "";
        if (!chapterName.isBlank()) {
            OutlineVO outline = store.getOutline(taskId);
            if (outline != null && outline.getChapters() != null) {
                for (OutlineVO.Chapter c : outline.getChapters()) {
                    boolean match = (target.getChapterId() != null && target.getChapterId().equals(c.getId()))
                            || chapterName.equals(XMindTrees.tidyTitle(c.getName()));
                    if (match) { chapterScope = c.getScope() == null ? "" : c.getScope(); break; }
                }
            }
        }

        // 目录补充生成用独立提示词：需求文档 + 当前分类/章节 + 用户补充测试内容，只生成当前分类类型的用例
        String caseSystem = testGenPromptService.getSystemPrompt("case_gen_manual_system");
        Map<String, String> p = new HashMap<>();
        p.put("extra", extraRequirement);
        p.put("category", category);
        p.put("chapterName", chapterName);
        p.put("chapterScope", chapterScope);

        TestGenTaskPO task = taskMapper.selectById(taskId);
        String truncatedDoc = docService.truncateDocText(docService.fetchDocText(taskId, task.getPrdName()));
        streamInto(taskId, nodeId, p, XMindTrees.tidyTitle(target.getTitle()),
                caseSystem, "case_gen_manual_user", truncatedDoc);
    }

    /**
     * 手动补充生成：流式为目标目录节点生成用例，直接追加到该节点下（不分类、不精修）。
     * promptParams：用户提示词模板占位参数（doc 由本方法统一补充）；logLabel：日志用的目标名称。
     */
    private void streamInto(Integer taskId, String targetNodeId, Map<String, String> promptParams,
                            String logLabel, String caseSystem, String caseUserKey, String truncatedDoc) {
        if (targetNodeId == null) return;
        String wsKey = String.valueOf(taskId);
        Object lock = store.getLock(taskId);

        store.addGeneratingNode(taskId, targetNodeId);
        try {
            Map<String, String> params = new HashMap<>();
            if (promptParams != null) params.putAll(promptParams);
            params.put("doc", truncatedDoc);
            String user = PromptLoader.loadWithParams(caseUserKey, params);

            int maxAttempts = 2;
            Exception lastErr = null;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                List<String> addedThisAttempt = new ArrayList<>();
                int[] caseCount = new int[]{0};
                try {
                    llmClient.streamJsonObjects(conn(), caseSystem, user, 180, 0.7, (jsonObj) -> {
                        try {
                            XMindNode caseNode = XMindTrees.buildSingleCaseNode(jsonObj);
                            if (caseNode == null) return;
                            synchronized (lock) {
                                XMindNode root = store.getTree(taskId);
                                XMindNode target = root != null ? XMindTrees.findNodeById(root, targetNodeId) : null;
                                if (target == null) return;
                                if (target.getChildren() == null) target.setChildren(new ArrayList<>());
                                target.getChildren().add(caseNode);
                                store.saveTree(taskId, root);
                                caseCount[0]++;
                                addedThisAttempt.add(caseNode.getId());
                                TestGenWebSocketHandler.sendNodeCasesGenerated(wsKey, targetNodeId,
                                        new ArrayList<>(target.getChildren()), false);
                            }
                        } catch (Exception ex) {
                            log.warn("处理单个用例失败，跳过: {}", ex.toString());
                        }
                    });
                    if (caseCount[0] > 0) { lastErr = null; break; }
                    lastErr = new RuntimeException("LLM 返回 0 条用例");
                    log.warn("目标[{}]用例第 {} 次生成 0 条，准备重试", logLabel, attempt);
                } catch (Exception ex) {
                    lastErr = ex;
                    log.warn("目标[{}]用例第 {} 次生成失败：{}", logLabel, attempt, ex.getMessage());
                }
                // 重试前移除本次已写入的用例，避免与下一次结果叠加
                if (lastErr != null && attempt < maxAttempts) {
                    synchronized (lock) {
                        XMindNode root = store.getTree(taskId);
                        if (root != null) {
                            for (String cid : addedThisAttempt) XMindTrees.removeNodeById(root, cid);
                            store.saveTree(taskId, root);
                        }
                    }
                    try { Thread.sleep(1500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }

            boolean failed = lastErr != null;
            synchronized (lock) {
                XMindNode root = store.getTree(taskId);
                XMindNode target = root != null ? XMindTrees.findNodeById(root, targetNodeId) : null;
                if (target != null) {
                    // 目标目录本身可能是失败重试入口（带 failed 标记），生成到用例后清除标记
                    if (XMindTrees.countNodes(target, "case") > 0) target.setIcons(null);
                    store.saveTree(taskId, root);
                    TestGenWebSocketHandler.sendNodeCasesGenerated(wsKey, targetNodeId,
                            new ArrayList<>(target.getChildren()), true);
                    TestGenWebSocketHandler.sendTreeUpdated(wsKey, root);
                }
            }
            if (failed) {
                TestGenWebSocketHandler.sendError(wsKey, "用例生成失败：" + lastErr.getMessage());
            }
        } catch (Exception e) {
            log.error("用例生成失败, taskId={}: {}", taskId, e.toString());
            TestGenWebSocketHandler.sendError(wsKey, "用例生成失败: " + e.getMessage());
        } finally {
            store.removeGeneratingNode(taskId, targetNodeId);
        }
    }

    // ---- 自动精修（去重 · 合并 · 补漏） ----

    /** 两级精修：① 按章节去重+合并+补漏；② 全局去重合并（均作用于用例） */
    private void refine(Integer taskId, String wsKey, OutlineVO outline, String truncatedDoc) {
        XMindNode root = store.getTree(taskId);
        if (root == null) return;
        if (XMindTrees.countNodes(root, "case") == 0) {
            log.info("taskId={} 现有用例为空，跳过精修", taskId);
            return;
        }

        TestGenWebSocketHandler.sendPhaseChanged(wsKey, "REFINING", "AI 正在精修用例（去重 · 合并 · 补漏）...");
        refineByChapter(taskId, wsKey, outline, truncatedDoc);
        refineGlobal(taskId, wsKey, outline, truncatedDoc);
    }

    /** 章节内精修分组：同一章节的用例可能分布在多个分类目录下，按 chapterId（回退章节名）聚合 */
    private static final class ChapterGroup {
        String chapterId;
        String name;
        final List<Map<String, Object>> cases = new ArrayList<>();
    }

    /** 章节级：把同一章节（跨分类）的用例聚合后独立去重+合并+补漏（并行） */
    private void refineByChapter(Integer taskId, String wsKey, OutlineVO outline, String doc) {
        XMindNode root = store.getTree(taskId);
        if (root == null || root.getChildren() == null) return;

        // 章节ID/名 -> scope，用于提示词入参
        Map<String, String> scopeById = new HashMap<>();
        Map<String, String> scopeByName = new HashMap<>();
        if (outline != null && outline.getChapters() != null) {
            for (OutlineVO.Chapter c : outline.getChapters()) {
                String scope = c.getScope() == null ? "" : c.getScope();
                if (c.getId() != null) scopeById.put(c.getId(), scope);
                if (c.getName() != null) scopeByName.put(XMindTrees.tidyTitle(c.getName()), scope);
            }
        }

        // 遍历 root→分类→章节，按章节聚合用例（同章节跨分类合并到一组）
        LinkedHashMap<String, ChapterGroup> groups = new LinkedHashMap<>();
        for (XMindNode categoryNode : root.getChildren()) {
            if (!"module".equals(categoryNode.getType()) || categoryNode.getChapterId() != null) continue;
            String category = XMindTrees.tidyTitle(categoryNode.getTitle());
            if (categoryNode.getChildren() == null) continue;
            for (XMindNode chapterNode : categoryNode.getChildren()) {
                if (!"module".equals(chapterNode.getType())) continue;
                String chapterName = XMindTrees.tidyTitle(chapterNode.getTitle());
                String key = chapterNode.getChapterId() != null ? chapterNode.getChapterId() : "name:" + chapterName;
                ChapterGroup g = groups.computeIfAbsent(key, k -> new ChapterGroup());
                g.chapterId = chapterNode.getChapterId();
                g.name = chapterName;
                g.cases.addAll(collectChapterCasesFull(category, chapterNode));
            }
        }

        List<ChapterGroup> ordered = new ArrayList<>();
        List<Map<String, String>> paramsList = new ArrayList<>();
        for (ChapterGroup g : groups.values()) {
            if (g.cases.isEmpty()) continue;
            String chapterScope = (g.chapterId != null && scopeById.containsKey(g.chapterId))
                    ? scopeById.get(g.chapterId)
                    : scopeByName.getOrDefault(g.name, "");
            Map<String, String> params = new HashMap<>();
            params.put("chapterName", g.name);
            params.put("chapterScope", chapterScope);
            params.put("cases", JSON.toJSONString(g.cases));
            params.put("doc", doc);
            ordered.add(g);
            paramsList.add(params);
        }
        if (paramsList.isEmpty()) return;

        TestGenWebSocketHandler.sendProgress(wsKey, "章节内精修（去重 · 合并 · 补漏），共 " + paramsList.size() + " 个章节...");
        String system = testGenPromptService.getSystemPrompt("refine_chapter_system");
        RefineBatch batch = runRefineCalls(taskId, paramsList, system, "refine_chapter_user");

        Object lock = store.getLock(taskId);
        int removed = 0, added = 0;
        synchronized (lock) {
            XMindNode current = store.getTree(taskId);
            if (current == null) return;
            // batch.results 与 ordered 顺序一致，按下标对回所属章节
            for (int i = 0; i < batch.results.size(); i++) {
                JSONObject res = batch.results.get(i);
                if (res == null) continue;
                removed += applyCaseDuplicateRemovals(current, res.getJSONArray("duplicateGroups"));
                ChapterGroup g = i < ordered.size() ? ordered.get(i) : null;
                if (g != null) {
                    added += applyCaseAdditions(current, g.name, g.chapterId, res.getJSONArray("additions"));
                }
            }
            store.saveTree(taskId, current);
        }
        String msg = "章节内精修完成：去重/合并 " + removed + " 条，补齐 " + added + " 条"
                + (batch.hasFailure() ? "（" + batch.errors.size() + " 个章节精修失败已跳过：" + batch.firstError() + "）" : "");
        log.info("taskId={} {}", taskId, msg);
        TestGenWebSocketHandler.sendProgress(wsKey, msg);
    }

    /** 全局去重合并（需求 + 大纲 + 全量用例，只删不补，做跨章节最终收敛） */
    private void refineGlobal(Integer taskId, String wsKey, OutlineVO outline, String doc) {
        XMindNode root = store.getTree(taskId);
        if (root == null) return;
        List<Map<String, String>> cases = collectAllCaseViews(root);
        if (cases.size() < 2) return;

        TestGenWebSocketHandler.sendProgress(wsKey, "全局精修（跨章节去重合并），共 " + cases.size() + " 条用例...");
        Map<String, String> params = new HashMap<>();
        params.put("outline", outline == null ? "" : JSON.toJSONString(outline.getChapters()));
        params.put("cases", JSON.toJSONString(cases));
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

        Object lock = store.getLock(taskId);
        int removed = 0;
        synchronized (lock) {
            XMindNode current = store.getTree(taskId);
            if (current == null) return;
            for (JSONObject res : batch.results) {
                if (res == null) continue;
                removed += applyCaseDuplicateRemovals(current, res.getJSONArray("duplicateGroups"));
            }
            store.saveTree(taskId, current);
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
                    String resp = llmClient.streamToString(conn(), system,
                            PromptLoader.loadWithParams(userPromptKey, params), 900, 0.5);
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

    private JSONObject parseRefineResponse(String text) {
        String json = LlmClient.extractFirstJsonObject(text);
        if (json == null) {
            log.warn("自动精修返回非法 JSON 对象：{}", text);
            return null;
        }
        try {
            return JSON.parseObject(json);
        } catch (Exception e) {
            log.warn("自动精修返回解析失败：{}: {}", text, e.toString());
            return null;
        }
    }

    /** 收集全树用例视图 {id, category, module(章节名), 用例名称}，给全局精修使用（root→分类→章节→用例） */
    private List<Map<String, String>> collectAllCaseViews(XMindNode root) {
        List<Map<String, String>> list = new ArrayList<>();
        if (root == null || root.getChildren() == null) return list;
        for (XMindNode categoryNode : root.getChildren()) {
            if (!"module".equals(categoryNode.getType()) || categoryNode.getChapterId() != null) continue;
            String category = XMindTrees.tidyTitle(categoryNode.getTitle());
            if (categoryNode.getChildren() == null) continue;
            for (XMindNode chapterNode : categoryNode.getChildren()) {
                if (!"module".equals(chapterNode.getType()) || chapterNode.getChildren() == null) continue;
                String module = XMindTrees.tidyTitle(chapterNode.getTitle());
                for (XMindNode caseNode : chapterNode.getChildren()) {
                    if (!"case".equals(caseNode.getType())) continue;
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("id", caseNode.getId());
                    m.put("category", category);
                    m.put("module", module);
                    m.put("用例名称", caseNode.getTitle());
                    list.add(m);
                }
            }
        }
        return list;
    }

    /**
     * 收集某章节模块下（用例为直接子节点）全部用例的完整结构化视图，供章节内精修做结构化合并：
     * {id, category, 用例名称, 优先级, 前置条件, 测试步骤:[{执行操作,预期结果}]}
     */
    private List<Map<String, Object>> collectChapterCasesFull(String category, XMindNode chapterNode) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (chapterNode == null || chapterNode.getChildren() == null) return list;
        for (XMindNode caseNode : chapterNode.getChildren()) {
            if (!"case".equals(caseNode.getType())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", caseNode.getId());
            m.put("category", category);
            m.putAll(XMindTrees.caseNodeToJson(caseNode));
            list.add(m);
        }
        return list;
    }

    /**
     * 应用去重/合并结果：removeIds 删除 case 节点。
     * merged（完整结构化用例，仅章节内精修下发）存在时，用合并后的完整用例整体重建 keep 节点内容
     * （用例名称/优先级/前置条件/测试步骤），保留其 id；全局精修不下发 merged，即纯去重。
     */
    private int applyCaseDuplicateRemovals(XMindNode root, JSONArray groups) {
        if (groups == null || groups.isEmpty()) return 0;
        List<String> tmp = new ArrayList<>();
        XMindTrees.collectCaseIds(root, tmp);
        Set<String> existingIds = new HashSet<>(tmp);
        int removed = 0;
        for (int i = 0; i < groups.size(); i++) {
            JSONObject g = groups.getJSONObject(i);
            JSONArray toRemove = g.getJSONArray("removeIds");
            String keepId = g.getString("keepId");
            if (toRemove == null) continue;
            // 合并：keepId 作为锚点保留，用合并后的完整用例重建其内容（结构化，含测试步骤合并）
            JSONObject merged = g.getJSONObject("merged");
            if (merged != null && !merged.isEmpty()) {
                XMindNode keepNode = (keepId != null && existingIds.contains(keepId))
                        ? XMindTrees.findNodeById(root, keepId) : null;
                // 合并缺少有效锚点，或重建失败时跳过本组，避免删掉原始用例却丢失合并结果
                if (keepNode == null) continue;
                XMindNode rebuilt = XMindTrees.buildSingleCaseNode(merged);
                if (rebuilt == null) continue;
                keepNode.setTitle(rebuilt.getTitle());
                keepNode.setIcons(rebuilt.getIcons());
                keepNode.setChildren(rebuilt.getChildren());
                // merged 可能带新的 category：把合并后的用例移动到更合适的分类目录（同章节下）
                XMindTrees.moveCaseToCategory(root, keepNode, merged.getString("category"));
            }
            for (int j = 0; j < toRemove.size(); j++) {
                String rid = toRemove.getString(j);
                if (rid == null || rid.isBlank()) continue;
                if (rid.equals(keepId)) continue; // 防御：keep 与 remove 撞 id 时不删
                if (!existingIds.contains(rid)) continue;
                if (XMindTrees.removeNodeById(root, rid)) {
                    removed++;
                    existingIds.remove(rid);
                }
            }
        }
        return removed;
    }

    /** 应用章节内补漏：additions 为完整用例（含 category），按 分类→章节 挂到对应目录下 */
    private int applyCaseAdditions(XMindNode root, String chapterName, String chapterId, JSONArray additions) {
        if (additions == null || additions.isEmpty() || root == null) return 0;
        int added = 0;
        for (int i = 0; i < additions.size(); i++) {
            JSONObject a = additions.getJSONObject(i);
            String name = a.getString("用例名称");
            if (name == null || name.isBlank()) continue;
            XMindNode caseNode = XMindTrees.buildSingleCaseNode(a);
            if (caseNode == null) continue;
            XMindTrees.placeCaseByCategoryChapter(root, chapterName, chapterId, a.getString("category"), caseNode);
            added++;
        }
        return added;
    }

    // ---- 内部工具 ----

    private LlmClient.Conn conn() {
        ModelConfig cfg = aiModelService.getThinking();
        return new LlmClient.Conn(cfg.getBaseUrl(), cfg.getApiKey(), cfg.getModel());
    }

    /** 提取 Throwable 的可读描述：Error（如 OOM/NoClassDefFound）往往无 message，需带上类名 */
    private String describeThrowable(Throwable e) {
        String msg = e.getMessage();
        if (msg != null && !msg.isBlank()) return msg;
        return e.getClass().getSimpleName();
    }
}
