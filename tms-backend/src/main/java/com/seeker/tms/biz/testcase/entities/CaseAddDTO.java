package com.seeker.tms.biz.testcase.entities;

import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotNull;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CaseAddDTO {
    @NotNull
    @ApiModelProperty("用例名称")
    private String name;

    @NotNull
    @ApiModelProperty("模块ID")
    private Integer moduleId;

    @NotNull
    @ApiModelProperty("优先级")
    private Integer priority;

    @ApiModelProperty("测试步骤")
    private List<StepAddDTO> steps;
}