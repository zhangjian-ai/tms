package com.seeker.tms.biz.device.entities;

import com.seeker.tms.common.enums.BoolStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class DeviceVO extends DevicePO {
    private BoolStatus status = BoolStatus.FALSE;
    private String holder;
}
