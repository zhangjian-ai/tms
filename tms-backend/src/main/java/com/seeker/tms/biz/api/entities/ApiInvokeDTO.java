package com.seeker.tms.biz.api.entities;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotNull;
import java.util.HashMap;

@Data
public class ApiInvokeDTO {
    @NotNull
    @ApiModelProperty("接口ID")
    private Integer apiId;

    @ApiModelProperty("请求头")
    private HashMap<String, String> headers;

    @ApiModelProperty("查询参数")
    private HashMap<String, String> params;

    @ApiModelProperty("请求体")
    private HashMap<String, Object> data;

    @ApiModelProperty("结果验证")
    private HashMap<String, Object> verify;
}
