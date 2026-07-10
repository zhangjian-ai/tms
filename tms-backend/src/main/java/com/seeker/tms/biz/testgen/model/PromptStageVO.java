package com.seeker.tms.biz.testgen.model;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * 阶段出参,供前端下拉选择;附带当前占用该阶段的提示词信息用于"接管"提示。
 */
@Data
public class PromptStageVO {
    private String stageKey;
    private String stageName;
    private String description;
    private Integer sortNo;

    @ApiModelProperty("当前占用该阶段的提示词ID,无则为空")
    private Integer boundPromptId;

    @ApiModelProperty("当前占用该阶段的提示词名称,无则为空")
    private String boundPromptName;
}
