package com.seeker.tms.biz.testgen.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum PointStatus {
    PENDING("PENDING", "待生成"),
    GENERATING("GENERATING", "生成中"),
    GENERATED("GENERATED", "已生成"),
    FAILED("FAILED", "失败");

    private final String code;
    private final String desc;
}
