package com.seeker.tms.biz.testgen.model;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class PromptDTO {

    @ApiModelProperty("提示词ID,新增不传,编辑必传")
    private Integer id;

    @NotBlank(message = "提示词名称不能为空")
    @ApiModelProperty("提示词显示名")
    private String name;

    @ApiModelProperty("绑定的阶段key,为空表示草稿(不生效)")
    private String stageKey;

    @NotBlank(message = "提示词内容不能为空")
    @ApiModelProperty("系统提示词内容")
    private String content;

    @ApiModelProperty("备注")
    private String remark;
}
