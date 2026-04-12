package com.seeker.tms.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum BoolStatus {
    TRUE(1),
    FALSE(0);

    @EnumValue
    @JsonValue
    private final int code;
}
