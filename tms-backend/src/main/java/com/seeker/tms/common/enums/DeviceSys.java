package com.seeker.tms.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum DeviceSys {

    Android("android"),
    IOS("ios"),
    Harmony("harmony");

    @EnumValue
    @JsonValue
    private final String name;
}
