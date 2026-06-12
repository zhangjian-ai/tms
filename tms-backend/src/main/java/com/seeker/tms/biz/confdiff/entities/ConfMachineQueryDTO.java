package com.seeker.tms.biz.confdiff.entities;

import com.seeker.tms.common.entities.PageReq;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class ConfMachineQueryDTO extends PageReq {

    @ApiModelProperty("机器名称,模糊匹配")
    private String name;

    @ApiModelProperty("主机地址,模糊匹配")
    private String host;
}
