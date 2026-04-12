package com.seeker.tms.biz.device.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.seeker.tms.biz.device.entities.DeviceConnectionPO;

public interface DeviceConnectionService extends IService<DeviceConnectionPO> {

    DeviceConnectionPO getConnectionById(Integer id);
}
