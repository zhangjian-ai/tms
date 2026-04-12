package com.seeker.tms.biz.device.entities;

import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotNull;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DeviceHoldDTO {

    @NotNull
    @ApiModelProperty("设备ID")
    private Integer id;

    @ApiModelProperty("设备占用者名字")
    private String holder;
}
