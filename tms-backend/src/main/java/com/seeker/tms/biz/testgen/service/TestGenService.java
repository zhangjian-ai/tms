package com.seeker.tms.biz.testgen.service;

import com.seeker.tms.biz.testgen.entities.*;

import java.util.List;

public interface TestGenService {
    Integer createTask(TaskCreateDTO dto);
    List<TestGenTaskPO> listTasks();
    TaskVO getTask(Integer taskId);
    XMindNode getXMindData(Integer taskId);
    void saveXMindData(Integer taskId, XMindNode root);
    void generatePoints(Integer taskId);
    void generateCasesForPoint(Integer taskId, String pointId);
    String finishTask(Integer taskId);
    void regenerateTask(Integer taskId);
    RestoreVO restoreTask(Integer taskId);
}
