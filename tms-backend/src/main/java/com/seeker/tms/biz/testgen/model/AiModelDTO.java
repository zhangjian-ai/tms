package com.seeker.tms.biz.testgen.model;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class AiModelDTO {

    @ApiModelProperty("模型ID,新增不传,编辑必传")
    private Integer id;

    @NotBlank(message = "模型名称不能为空")
    @ApiModelProperty("模型显示名称")
    private String name;

    @NotBlank(message = "base-url不能为空")
    @ApiModelProperty("OpenAI 兼容接口 base-url")
    private String baseUrl;

    @ApiModelProperty("接口密钥,编辑时留空表示不修改")
    private String apiKey;

    @NotBlank(message = "模型标识不能为空")
    @ApiModelProperty("实际模型标识,如 gpt-5.5")
    private String modelName;

    @ApiModelProperty("是否用作 vision 模型")
    private Boolean useAsVision = false;

    @ApiModelProperty("是否用作 thinking 模型")
    private Boolean useAsThinking = false;

    @ApiModelProperty("备注")
    private String remark;
}
