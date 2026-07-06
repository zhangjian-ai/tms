package com.seeker.tms.biz.testgen.model;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型出参,敏感字段(apiKey)脱敏不返回
 */
@Data
public class AiModelVO {
    private Integer id;
    private String name;
    private String baseUrl;
    private String modelName;
    private Boolean useAsVision;
    private Boolean useAsThinking;
    private String remark;

    @ApiModelProperty("是否已配置密钥")
    private Boolean hasApiKey;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
