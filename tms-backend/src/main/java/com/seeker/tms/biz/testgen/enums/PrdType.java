package com.seeker.tms.biz.testgen.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum PrdType {
    BIZ("BIZ", "业务需求"),
    BURY("BURY", "埋点需求"),
    API("API", "接口需求");

    private final String code;
    private final String desc;
}
