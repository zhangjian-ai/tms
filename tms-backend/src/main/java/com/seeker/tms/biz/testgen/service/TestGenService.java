package com.seeker.tms.biz.testgen.service;

import com.seeker.tms.biz.testgen.entities.*;
import com.seeker.tms.common.entities.PageResult;

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
}
