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

            // holder 现以 JSON({username,sessionId}) 存储，展示只取 username；兼容旧的纯字符串值
            String holderName = null;
            if (holder != null) {
                try {
                    holderName = JSONObject.parseObject(holder.toString()).getString("username");
                } catch (Exception e) {
                    holderName = holder.toString();
                }
            }

            // 可用 = 在线 且 未被占用（沿用原判定，holder 为原始对象）
            deviceVO.setStatus((status != null && (int)status == 1 && holder == null) ? BoolStatus.TRUE : BoolStatus.FALSE);
            deviceVO.setHolder(holderName);
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

        String key = redisConfig.getHolderPrefix() + devicePo.getSerial();

        if (deviceHoldDTO.getHolder() != null) {
            // 占用：原子写入，先到先得。值携带 sessionId 以便后续按会话校验所有权
            JSONObject value = new JSONObject();
            value.put("username", deviceHoldDTO.getHolder());
            value.put("sessionId", deviceHoldDTO.getSessionId());
            return Boolean.TRUE.equals(redisTemplate.opsForValue()
                    .setIfAbsent(key, value.toJSONString(), 10, TimeUnit.SECONDS));
        }

        // 释放：仅当会话匹配才删除，避免其它页面（未持有本会话）误删原页面的占用
        Object current = redisTemplate.opsForValue().get(key);
        if (current == null) return true;

        String storedSessionId = null;
        try {
            storedSessionId = JSONObject.parseObject(current.toString()).getString("sessionId");
        } catch (Exception e) {
            // 旧格式/非 JSON 值：无会话信息，按可清理处理
        }

        if (storedSessionId == null || storedSessionId.equals(deviceHoldDTO.getSessionId())) {
            redisTemplate.delete(key);
        }
        return true;
    }
}
