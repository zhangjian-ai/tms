package com.seeker.tms.biz.device.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.seeker.tms.biz.device.entities.DeviceHoldDTO;
import com.seeker.tms.biz.device.entities.DevicePO;
import com.seeker.tms.biz.device.entities.DeviceQueryDTO;
import com.seeker.tms.biz.device.entities.DeviceVO;
import com.seeker.tms.common.entities.PageResult;


public interface DeviceService extends IService<DevicePO> {

    PageResult<DeviceVO> deviceList(DeviceQueryDTO deviceQueryDTO);

    DevicePO detailById(Integer id);

    boolean deviceHold(DeviceHoldDTO deviceHoldDTO);

}
