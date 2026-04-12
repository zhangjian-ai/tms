package com.seeker.tms.biz.device.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.seeker.tms.biz.device.entities.DeviceHoldDTO;
import com.seeker.tms.biz.device.entities.DevicePO;
import com.seeker.tms.biz.device.entities.DeviceQueryDTO;
import com.seeker.tms.biz.device.entities.DeviceVO;
import com.seeker.tms.biz.device.mapper.DeviceMapper;
import com.seeker.tms.biz.device.service.DeviceService;
import com.seeker.tms.common.config.RedisConfig;
import com.seeker.tms.common.entities.PageResult;
import com.seeker.tms.common.enums.BoolStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;


@Slf4j
@Service
public class DeviceServiceImpl extends ServiceImpl<DeviceMapper, DevicePO> implements DeviceService {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Resource
    private RedisConfig redisConfig;

    @Override
    public PageResult<DeviceVO> deviceList(DeviceQueryDTO deviceQueryDTO) {
        // 分页器
        Page<DevicePO> page = Page.of(deviceQueryDTO.getPageNo(), deviceQueryDTO.getPageSize());

        if (StrUtil.isNotBlank(deviceQueryDTO.getSortBy())) {
            page.addOrder(new OrderItem(deviceQueryDTO.getSortBy(), deviceQueryDTO.isAsc()));
        } else {
            page.addOrder(new OrderItem("update_time", deviceQueryDTO.isAsc()));
        }

        // 条件查询
        this.lambdaQuery()
                .like(StrUtil.isNotBlank(deviceQueryDTO.getName()), DevicePO::getName, deviceQueryDTO.getName())
                .eq(StrUtil.isNotBlank(deviceQueryDTO.getSerial()), DevicePO::getSerial, deviceQueryDTO.getSerial())
                .eq(StrUtil.isNotBlank(deviceQueryDTO.getBrand()), DevicePO::getBrand, deviceQueryDTO.getBrand())
                .eq(deviceQueryDTO.getDeviceSys() != null, DevicePO::getDeviceSys, deviceQueryDTO.getDeviceSys())
                .eq(StrUtil.isNotBlank(deviceQueryDTO.getOsVersion()), DevicePO::getOsVersion, deviceQueryDTO.getOsVersion())
                .page(page);

        // 构建响应数据
        PageResult<DeviceVO> devicePoPageResult = new PageResult<>();
        devicePoPageResult.setTotal((int) page.getTotal());
        devicePoPageResult.setPageNo((int) page.getCurrent());
        devicePoPageResult.setPageCount((int) page.getPages());

        // 所有记录
        List<DevicePO> devicePOS = page.getRecords();

        // 动态更新设备在前端的展示状态
        ArrayList<DeviceVO> deviceVOS = new ArrayList<>(devicePOS.size());
        for (DevicePO devicePo : devicePOS) {
            DeviceVO deviceVO = BeanUtil.copyProperties(devicePo, DeviceVO.class);

            // 获取设备实时状态
            Object status = redisTemplate.opsForValue().get(redisConfig.getStatusPrefix() + devicePo.getSerial());
            Object holder = redisTemplate.opsForValue().get(redisConfig.getHolderPrefix() + devicePo.getSerial());

            deviceVO.setStatus((status != null && (int)status == 1 && holder == null) ? BoolStatus.TRUE : BoolStatus.FALSE);
            deviceVO.setHolder((String) holder);
            deviceVOS.add(deviceVO);
        }

        devicePoPageResult.setList(deviceVOS);
        return devicePoPageResult;
    }

    @Override
    public DevicePO detailById(Integer id) {
        // 跟id查询数据
        DevicePO devicePo = this.getById(id);

        if (devicePo == null) {
            throw new IllegalArgumentException("无效的设备ID: " + id.toString());
        }

        return devicePo;
    }

    @Override
    public boolean deviceHold(DeviceHoldDTO deviceHoldDTO) {
        DevicePO devicePo = this.getById(deviceHoldDTO.getId());

        if (devicePo == null) return false;

        if (deviceHoldDTO.getHolder() != null){
            // 尝试占用设备
            return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(redisConfig.getHolderPrefix() + devicePo.getSerial(), deviceHoldDTO.getHolder(), 10, TimeUnit.SECONDS));
        }
        // 删除设备占用信息
        redisTemplate.opsForValue().getAndDelete(redisConfig.getHolderPrefix() + devicePo.getSerial());
        return true;
    }
}
