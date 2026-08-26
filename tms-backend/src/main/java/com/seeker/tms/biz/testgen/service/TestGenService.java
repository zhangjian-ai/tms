package com.seeker.tms.biz.testgen.service;

import com.seeker.tms.biz.testgen.entities.*;
import com.seeker.tms.common.entities.PageResult;

import java.util.List;

public interface TestGenService {
    Integer createTask(TaskCreateDTO dto);
    PageResult<TestGenTaskPO> pageTasks(TaskQueryDTO query);
    TaskVO getTask(Integer taskId);
    XMindNode getXMindData(Integer taskId);
    void saveXMindData(Integer taskId, XMindNode root);
    void generateOutline(Integer taskId);
    void confirmPlan(Integer taskId, OutlineVO outline);
    OutlineVO getOutline(Integer taskId);
    void generateCasesForNode(Integer taskId, String nodeId, String extraRequirement);
    String finishTask(Integer taskId);
    void regenerateTask(Integer taskId);
    RestoreVO restoreTask(Integer taskId);
    void deleteTask(Integer taskId);

    // ---- AI 调试（用户驱动的二次优化） ----
    /** 提案：调 LLM 产出删/改/增提案（不改树），并记录一条调试历史 */
    AiDebugResultVO aiDebugPropose(Integer taskId, AiDebugRequestDTO req);
    /** 应用：把用户确认的删/改/增写入用例树并刷新，返回新树 */
    XMindNode aiDebugApply(Integer taskId, AiDebugApplyDTO dto);
    /** 读取当前用户在该任务下的调试历史（最新在前，最多 30 条） */
    List<AiDebugHistoryVO> getAiDebugHistory(Integer taskId);
}

