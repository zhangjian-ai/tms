package com.seeker.tms.biz.testgen.entities;

import lombok.Data;

import java.util.List;

@Data
public class RestoreVO {
    private TaskVO task;
    private XMindNode treeData;
    private List<String> generatingNodeIds;
    /** 当任务停留在 PLAN_REVIEW 阶段时，回传当前大纲，前端可恢复确认面板 */
    private OutlineVO outline;
    /** 任务正被其他用户编辑时的占用者用户名；无人占用或占用者即本人时为 null。前端据此进入只读态 */
    private String occupiedBy;
}
