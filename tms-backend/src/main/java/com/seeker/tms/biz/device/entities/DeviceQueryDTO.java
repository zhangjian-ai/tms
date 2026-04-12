package com.seeker.tms.biz.device.entities;

import com.seeker.tms.common.entities.PageReq;
import com.seeker.tms.common.enums.DeviceSys;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;


@EqualsAndHashCode(callSuper = true)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceQueryDTO extends PageReq {

    @ApiModelProperty("设备名称，模糊匹配")
    private String name;

    @ApiModelProperty("设备序列号，精准匹配")
    private String serial;

    @ApiModelProperty("品牌，精准匹配")
    private String brand;

    @ApiModelProperty("操作系统，精准匹配")
    private DeviceSys deviceSys;

    @ApiModelProperty("系统版本，精准匹配")
    private String osVersion;

}
