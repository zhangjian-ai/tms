package com.seeker.tms.biz.testgen.model;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 提示词出参。列表不返回 content(体积大),仅详情返回。
 */
@Data
public class PromptVO {
    private Integer id;
    private String name;
    private String stageKey;

    @ApiModelProperty("绑定阶段的显示名,草稿为空")
    private String stageName;

    @ApiModelProperty("提示词内容,仅详情接口返回")
    private String content;

    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
