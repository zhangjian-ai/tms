package com.seeker.tms.biz.testcase.entities;

import com.seeker.tms.common.enums.BoolStatus;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotNull;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class StepAddDTO {

    @NotNull
    @ApiModelProperty("步骤详情")
    private String detail;

    @ApiModelProperty("预期结果")
    private String result;

    @ApiModelProperty("是否是前置条件，默认0（不是）")
    private BoolStatus isCondition = BoolStatus.FALSE;
}