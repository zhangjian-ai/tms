package com.seeker.tms.biz.confdiff.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 机器 SSH 鉴权方式
 */
@AllArgsConstructor
@Getter
public enum AuthType {

    PASSWORD("password"),
    PRIVATE_KEY("private_key");

    @EnumValue
    @JsonValue
    private final String value;
}
