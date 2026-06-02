package com.seeker.tms.biz.testgen.entities;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class RestoreVO {
    private TaskVO task;
    private XMindNode treeData;
    private List<Map<String, String>> chatHistory;
    private List<String> generatingPointIds;
    /** 当任务停留在 PLAN_REVIEW 阶段时，回传当前大纲，前端可恢复确认面板 */
    private OutlineVO outline;
}
