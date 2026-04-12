package com.seeker.tms.biz.api.entities;

import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotNull;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiAddDTO {
    @NotNull
    @ApiModelProperty("接口名称")
    private String name;

    @NotNull
    @ApiModelProperty("协议")
    private String proto;

    @NotNull
    @ApiModelProperty("请求地址")
    private String url;

    @NotNull
    @ApiModelProperty("请求方法")
    private String method;
}
