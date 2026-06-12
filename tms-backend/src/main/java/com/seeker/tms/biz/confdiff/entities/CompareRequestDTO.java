package com.seeker.tms.biz.confdiff.entities;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;

@Data
public class CompareRequestDTO {

    @NotNull(message = "项目ID不能为空")
    @ApiModelProperty("项目ID")
    private Integer projectId;

    @NotNull(message = "对比基准侧不能为空")
    @Valid
    @ApiModelProperty("对比基准侧(分支+commit)")
    private CompareRef refA;

    @NotNull(message = "对比目标侧不能为空")
    @Valid
    @ApiModelProperty("对比目标侧(分支+commit)")
    private CompareRef refB;
}
