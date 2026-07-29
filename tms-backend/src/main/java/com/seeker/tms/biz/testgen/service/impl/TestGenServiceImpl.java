package com.seeker.tms.biz.testgen.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.seeker.tms.biz.testgen.entities.*;
import com.seeker.tms.biz.testgen.enums.TaskStatus;
import com.seeker.tms.biz.testgen.mapper.TestGenTaskMapper;
import com.seeker.tms.biz.testgen.service.AiModelService;
import com.seeker.tms.biz.testgen.service.DocumentParserService;
import com.seeker.tms.biz.testgen.service.TestGenService;
import com.seeker.tms.biz.testgen.utils.XMindTrees;
import com.seeker.tms.biz.testgen.websocket.TestGenWebSocketHandler;
import com.seeker.tms.common.docsource.DocumentLinkService;
import com.seeker.tms.common.entities.PageResult;
import com.seeker.tms.common.utils.MinioUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * 用例生成任务编排：任务 CRUD、生成/精修流程的状态与阶段推送、完成导出、重新生成、恢复、删除。
 * 具体能力委派给协作单元——文档 {@link RequirementDocService}、生成/精修 {@link CaseWorkflow}、
 * 共享树状态 {@link TestGenStore}、纯树工具 {@link XMindTrees}。
 */
@Slf4j
@Service
@AllArgsConstructor
public class TestGenServiceImpl extends ServiceImpl<TestGenTaskMapper, TestGenTaskPO>
        implements TestGenService {

    private final TestGenTaskMapper taskMapper;
    private final DocumentParserService documentParserService;
    private final DocumentLinkService documentLinkService;
    private final AiModelService aiModelService;
    private final MinioUtil minioUtil;
    private final TestGenStore store;
    private final RequirementDocService docService;
    private final CaseWorkflow caseWorkflow;

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
            List<String> links = docService.splitPrdNames(dto.getPrdName());
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

        // 上传的原始文档以原始文件名存储在 MinIO（由 /common/file/upload 完成），
        // 这里不再迁移到任务维度对象键：任务最终用的是处理后的全量文档，重新生成也复用全量文档，
        // 删除任务时按文档原始文件名直接删除即可。

        // 强制清掉该 id 名下的旧文档缓存，避免新任务文档不生效
        docService.clearParsedCache(task.getId());

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

    @Override
    public XMindNode getXMindData(Integer taskId) {
        return store.getTree(taskId);
    }

    @Override
    public void saveXMindData(Integer taskId, XMindNode root) {
        store.saveTree(taskId, root);
    }

    @Override
    public OutlineVO getOutline(Integer taskId) {
        return store.getOutline(taskId);
    }

    // ---- 阶段 A：生成章节大纲（流式） ----

    @Override
    @Async("taskExecutor")
    public void generateOutline(Integer taskId) {
        String wsKey = String.valueOf(taskId);
        TestGenTaskPO task = taskMapper.selectById(taskId);
        if (task == null) return;

        try {
            updateStatus(taskId, TaskStatus.PLANNING.getCode(), "正在下载并解析需求文档...");
            TestGenWebSocketHandler.sendTaskStatus(wsKey,
                    TaskStatus.PLANNING.getCode(), "正在下载并解析需求文档...");
            TestGenWebSocketHandler.sendPhaseChanged(wsKey, "PLANNING", "正在下载并解析需求文档...");
            TestGenWebSocketHandler.sendProgress(wsKey, "正在下载需求文档...");

            String docText = docService.fetchDocText(taskId, task.getPrdName(), wsKey);

            updateStatus(taskId, TaskStatus.PLANNING.getCode(), "正在生成需求章节摘要...");
            TestGenWebSocketHandler.sendProgress(wsKey, "正在生成需求章节摘要...");

            OutlineVO outline = caseWorkflow.plan(docService.truncateDocText(docText));

            // 暂存大纲并通知前端
            store.saveOutline(taskId, outline);
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

    // ---- 阶段 B：确认大纲并触发用例生成 ----

    @Override
    @Async("taskExecutor")
    public void confirmPlan(Integer taskId, OutlineVO outline) {
        String wsKey = String.valueOf(taskId);
        TestGenTaskPO task = taskMapper.selectById(taskId);
        if (task == null) return;

        // 用户可能调整过大纲，以传入为准；空则走暂存版本
        OutlineVO effective = outline != null ? outline : store.getOutline(taskId);
        if (effective == null || effective.getChapters() == null || effective.getChapters().isEmpty()) {
            TestGenWebSocketHandler.sendError(wsKey, "大纲为空，请先生成或填写章节");
            // 还原状态，避免前端因预设 GENERATING 而卡在遮罩
            TestGenWebSocketHandler.sendTaskStatus(wsKey,
                    TaskStatus.PLAN_REVIEW.getCode(), "大纲为空，请先生成或填写章节");
            return;
        }
        // 过滤空章节名，并补章节内部ID（用例按章节分组做章节内精修）
        List<OutlineVO.Chapter> chapters = new ArrayList<>();
        for (OutlineVO.Chapter c : effective.getChapters()) {
            String n = c.getName() == null ? "" : c.getName().trim();
            if (n.isEmpty()) continue;
            if (c.getId() == null || c.getId().isBlank()) c.setId("chapter_" + UUID.randomUUID());
            chapters.add(c);
        }
        if (chapters.isEmpty()) {
            TestGenWebSocketHandler.sendError(wsKey, "大纲章节为空，请先填写章节");
            TestGenWebSocketHandler.sendTaskStatus(wsKey,
                    TaskStatus.PLAN_REVIEW.getCode(), "大纲章节为空，请先填写章节");
            return;
        }
        effective.setChapters(chapters);
        store.saveOutline(taskId, effective);

        try {
            updateStatus(taskId, TaskStatus.GENERATING.getCode(), "正在按章节生成用例...");
            TestGenWebSocketHandler.sendTaskStatus(wsKey,
                    TaskStatus.GENERATING.getCode(), "正在按章节生成用例...");
            TestGenWebSocketHandler.sendPhaseChanged(wsKey, "GENERATING_CASES", "正在按章节生成用例...");

            String docText = docService.fetchDocText(taskId, task.getPrdName(), wsKey);
            int[] counts = caseWorkflow.runGeneration(taskId, task.getPrdName(), effective, docText);
            int caseCount = counts[0];
            int failedChapters = counts[1];

            String doneMsg = "用例生成完成，共 " + caseCount + " 条用例"
                    + (failedChapters > 0 ? "（" + failedChapters + " 个章节失败，可右键单独重试）" : "");
            updateStatus(taskId, TaskStatus.EDITING.getCode(), doneMsg);
            TestGenWebSocketHandler.sendTaskStatus(wsKey, TaskStatus.EDITING.getCode(), doneMsg);
            TestGenWebSocketHandler.sendTreeUpdated(wsKey, store.getTree(taskId));
            TestGenWebSocketHandler.sendPhaseChanged(wsKey, "EDITING", "用例生成完成");
        } catch (Throwable e) {
            log.error("用例生成失败，taskId={}: {}", taskId, e.toString());
            store.endGenerating(taskId);
            String reason = describeThrowable(e);
            updateStatus(taskId, TaskStatus.FAILED.getCode(), "失败：" + reason);
            TestGenWebSocketHandler.sendError(wsKey, reason);
        }
    }

    // ---- 目录节点手动生成用例 ----

    @Override
    @Async("taskExecutor")
    public void generateCasesForNode(Integer taskId, String nodeId, String extraRequirement) {
        caseWorkflow.generateForNode(taskId, nodeId, extraRequirement);
    }

    // ---- 完成任务 ----

    @Override
    public String finishTask(Integer taskId) {
        XMindNode root = store.getTree(taskId);
        if (root == null) throw new RuntimeException("暂无数据");

        TestGenTaskPO taskPO = taskMapper.selectById(taskId);
        // 文件名加 taskId 后缀避免同需求文档不同任务相互覆盖/误删
        String base = docService.buildRootTitle(taskId, taskPO.getPrdName()) + "_" + taskId;
        String fileName = base + ".xmind";

        XMindNode exportRoot = XMindTrees.filterForExport(root);
        byte[] xmindBytes = XMindBuilder.build(exportRoot);
        minioUtil.uploadFile(fileName, xmindBytes);

        TestGenTaskPO update = new TestGenTaskPO();
        update.setId(taskId);
        update.setStatus(TaskStatus.FINISHED.getCode());
        update.setXmindFileName(fileName);
        update.setUpdateTime(LocalDateTime.now());

        // 同时导出一份用例 Excel（尽力而为：失败不影响任务完成与 XMind 下载）
        try {
            String excelName = base + ".xlsx";
            byte[] excelBytes = CaseExcelBuilder.build(root, taskPO.getCreator());
            minioUtil.uploadFile(excelName, excelBytes,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            update.setExcelFileName(excelName);
        } catch (Exception e) {
            log.warn("生成用例 Excel 失败, taskId={}: {}", taskId, e.toString());
        }

        taskMapper.updateById(update);

        clearTaskCaches(taskId);

        TestGenWebSocketHandler.closeAllSessions(taskId.toString());

        return fileName;
    }

    // ---- 重新生成 ----

    @Override
    public void regenerateTask(Integer taskId) {
        // 删除旧的导出文件（XMind + Excel）
        TestGenTaskPO oldTask = taskMapper.selectById(taskId);
        if (oldTask != null) {
            deleteFileQuietly(oldTask.getXmindFileName());
            deleteFileQuietly(oldTask.getExcelFileName());
        }

        clearTaskCaches(taskId);
        // 不清理已解析文本缓存：重新生成的文档与解析开关均不可变（无编辑入口），
        // 全量需求文档不会变，直接复用 testgen_{taskId}.parsed.txt，省去重复下载/解析/整合。

        // 用 lambdaUpdate 显式 set(null)：MyBatis-Plus 默认 NOT_NULL 更新策略下
        // updateById 会忽略为 null 的字段，导致 xmind/excel 文件名残留、任务仍显示为有可下载结果。
        this.lambdaUpdate()
                .eq(TestGenTaskPO::getId, taskId)
                .set(TestGenTaskPO::getStatus, TaskStatus.NEW.getCode())
                .set(TestGenTaskPO::getMessage, null)
                .set(TestGenTaskPO::getXmindFileName, null)
                .set(TestGenTaskPO::getExcelFileName, null)
                .set(TestGenTaskPO::getUpdateTime, LocalDateTime.now())
                .update();
        // 不调用 closeAllSessions：发起 regenerate 的用户 ws 仍需用于接收新一轮推送
    }

    // ---- 恢复状态 ----

    @Override
    public RestoreVO restoreTask(Integer taskId) {
        RestoreVO vo = new RestoreVO();
        TaskVO taskVO = getTask(taskId);
        OutlineVO outline = store.getOutline(taskId);

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
        vo.setTreeData(store.getTree(taskId));
        vo.setGeneratingNodeIds(store.generatingNodeIds(taskId));
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

        // 删除 MinIO 中的导出结果文件（XMind + Excel）
        deleteFileQuietly(task.getXmindFileName());
        deleteFileQuietly(task.getExcelFileName());

        // 删除原始需求文档（主文档 + 关联文档）：以原始文件名存储在 MinIO，直接按文件名删除。
        // 上传可能是多个文件，逐个删除。LINK 来源为远程链接，不涉及 MinIO 文件。
        if (!"LINK".equalsIgnoreCase(task.getPrdSource())) {
            List<String> docNames = new ArrayList<>();
            docNames.addAll(docService.splitPrdNames(task.getPrdName()));
            docNames.addAll(docService.splitPrdNames(task.getRelatedNames()));
            for (String name : new LinkedHashSet<>(docNames)) {
                try {
                    minioUtil.deleteFile(name);
                    log.info("已删除原始需求文档: {}", name);
                } catch (Exception e) {
                    log.warn("删除原始需求文档失败: {}: {}", name, e.toString());
                }
            }
        }

        clearTaskCaches(taskId);
        docService.clearParsedCache(taskId);
        log.info("已清理 Redis 缓存: taskId={}", taskId);

        taskMapper.deleteById(taskId);
        log.info("已删除任务记录: taskId={}", taskId);

        TestGenWebSocketHandler.closeAllSessions(taskId.toString());
    }

    // ---- 内部工具 ----

    /** 删除 MinIO 文件，忽略不存在/失败（清理用途，不应中断主流程） */
    private void deleteFileQuietly(String fileName) {
        if (fileName == null || fileName.isBlank()) return;
        try {
            minioUtil.deleteFile(fileName);
            log.info("已删除文件: {}", fileName);
        } catch (Exception e) {
            log.warn("删除文件失败: {}: {}", fileName, e.toString());
        }
    }

    /** 清理任务的共享运行时状态（Redis 树/大纲 + 内存态 Map）与文档标题缓存，用于 完成/重新生成/删除 */
    private void clearTaskCaches(Integer taskId) {
        store.clearState(taskId);
        docService.clearTitles(taskId);
    }

    private void updateStatus(Integer taskId, String status, String message) {
        TestGenTaskPO t = new TestGenTaskPO();
        t.setId(taskId);
        t.setStatus(status);
        t.setMessage(message);
        t.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(t);
    }

    /** 提取 Throwable 的可读描述：Error（如 OOM/NoClassDefFound）往往无 message，需带上类名 */
    private String describeThrowable(Throwable e) {
        String msg = e.getMessage();
        if (msg != null && !msg.isBlank()) return msg;
        return e.getClass().getSimpleName();
    }
}
