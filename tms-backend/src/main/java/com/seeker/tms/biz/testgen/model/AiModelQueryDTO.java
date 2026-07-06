package com.seeker.tms.biz.testgen.model;

import com.seeker.tms.common.entities.PageReq;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class AiModelQueryDTO extends PageReq {

    @ApiModelProperty("模型名称,模糊匹配")
    private String name;
}
