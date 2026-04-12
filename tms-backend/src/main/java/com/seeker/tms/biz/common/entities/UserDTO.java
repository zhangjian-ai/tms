package com.seeker.tms.biz.common.entities;

import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotNull;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserDTO {

    @NotNull
    @ApiModelProperty("用户名")
    private String username;

    @NotNull
    @ApiModelProperty("用户密码")
    private String password;
}
