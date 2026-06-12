package com.seeker.tms.biz.confdiff.entities;

import com.seeker.tms.common.entities.PageReq;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class ConfProjectQueryDTO extends PageReq {

    @ApiModelProperty("所属机器ID,精准匹配")
    private Integer machineId;

    @ApiModelProperty("项目名称,模糊匹配")
    private String name;
}
