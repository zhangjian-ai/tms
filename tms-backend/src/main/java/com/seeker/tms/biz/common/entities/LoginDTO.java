package com.seeker.tms.biz.common.entities;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 三方登录回调入参。
 */
@Data
@ApiModel("三方登录请求")
public class LoginDTO {

    @NotBlank(message = "授权码不能为空")
    @ApiModelProperty(value = "授权码 code", required = true)
    private String code;

    @ApiModelProperty("防 CSRF 的 state")
    private String state;
}
