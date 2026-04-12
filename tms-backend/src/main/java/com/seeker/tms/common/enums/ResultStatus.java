package com.seeker.tms.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ResultStatus {
    SUCCESS("success", 0),
    FAILED("failed", -1);

    private final String value;

    private final int code;
}
