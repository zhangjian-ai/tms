package com.seeker.tms.biz.testgen.entities;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
@ApiModel("创建任务请求")
public class TaskCreateDTO {
    @ApiModelProperty(value = "需求文档名称（带后缀）", required = true)
    @NotBlank(message = "需求文档名称不能为空")
    private String prdName;

    @ApiModelProperty(value = "需求类型: BIZ/BURY/API", required = true)
    @NotBlank(message = "需求类型不能为空")
    private String prdType;

    @ApiModelProperty("创建人")
    private String creator;
}
