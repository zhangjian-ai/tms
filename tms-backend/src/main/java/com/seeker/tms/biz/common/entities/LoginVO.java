package com.seeker.tms.biz.common.entities;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录结果：登录 token + 用户展示信息。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@ApiModel("登录结果")
public class LoginVO {

    @ApiModelProperty("登录 token")
    private String token;

    @ApiModelProperty("用户名(显示名，唯一标识)")
    private String username;

    @ApiModelProperty("头像URL")
    private String avatar;
}
