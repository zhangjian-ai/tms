package com.seeker.tms.biz.device.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.seeker.tms.biz.device.entities.DeviceConnectionPO;
import com.seeker.tms.biz.device.mapper.DeviceConnectionMapper;
import com.seeker.tms.biz.device.service.DeviceConnectionService;
import org.springframework.stereotype.Service;

@Service
public class DeviceConnectionServiceImpl extends ServiceImpl<DeviceConnectionMapper, DeviceConnectionPO> implements DeviceConnectionService {

    @Override
    public DeviceConnectionPO getConnectionById(Integer id) {
        DeviceConnectionPO deviceConnectionPo = this.lambdaQuery().eq(DeviceConnectionPO::getDeviceId, id).one();

        if (deviceConnectionPo == null){
            throw new IllegalArgumentException("无效的设备ID: " + id.toString());
        }

        return deviceConnectionPo;
    }
}
