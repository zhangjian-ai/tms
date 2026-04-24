package com.seeker.tms.biz.testgen.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum TaskStatus {
    NEW("NEW", "新建"),
    GENERATING("GENERATING", "生成中"),
    EDITING("EDITING", "编辑中"),
    FINISHED("FINISHED", "已完成"),
    FAILED("FAILED", "失败");

    private final String code;
    private final String desc;
}
