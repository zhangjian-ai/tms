package com.seeker.tms.biz.testgen.model;

import com.seeker.tms.common.entities.PageReq;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class PromptQueryDTO extends PageReq {

    @ApiModelProperty("提示词名称,模糊匹配")
    private String name;

    @ApiModelProperty("按阶段key筛选")
    private String stageKey;
}
