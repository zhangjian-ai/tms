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
import com.seeker.tms.biz.device.websocket.DeviceSyncHandler;
import com.seeker.tms.common.config.RedisConfig;
import com.seeker.tms.common.entities.PageResult;
import com.seeker.tms.common.enums.BoolStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
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
        Page<DevicePO> page = Page.of(deviceQueryDTO.getPageNo(), deviceQueryDTO.getPageSize());

        if (StrUtil.isNotBlank(deviceQueryDTO.getSortBy())) {
            page.addOrder(new OrderItem(deviceQueryDTO.getSortBy(), deviceQueryDTO.isAsc()));
        } else {
            page.addOrder(new OrderItem("update_time", deviceQueryDTO.isAsc()));
        }

        this.lambdaQuery()
                .like(StrUtil.isNotBlank(deviceQueryDTO.getName()), DevicePO::getName, deviceQueryDTO.getName())
                .eq(StrUtil.isNotBlank(deviceQueryDTO.getSerial()), DevicePO::getSerial, deviceQueryDTO.getSerial())
                .eq(StrUtil.isNotBlank(deviceQueryDTO.getBrand()), DevicePO::getBrand, deviceQueryDTO.getBrand())
                .eq(deviceQueryDTO.getDeviceSys() != null, DevicePO::getDeviceSys, deviceQueryDTO.getDeviceSys())
                .eq(StrUtil.isNotBlank(deviceQueryDTO.getOsVersion()), DevicePO::getOsVersion, deviceQueryDTO.getOsVersion())
                .page(page);

        PageResult<DeviceVO> devicePoPageResult = new PageResult<>();
        devicePoPageResult.setTotal((int) page.getTotal());
        devicePoPageResult.setPageNo((int) page.getCurrent());
        devicePoPageResult.setPageCount((int) page.getPages());

        List<DevicePO> devicePOS = page.getRecords();

        // 动态更新设备在前端的展示状态
        ArrayList<DeviceVO> deviceVOS = new ArrayList<>(devicePOS.size());
        for (DevicePO devicePo : devicePOS) {
            DeviceVO deviceVO = BeanUtil.copyProperties(devicePo, DeviceVO.class);

            Object status = redisTemplate.opsForValue().get(redisConfig.getStatusPrefix() + devicePo.getSerial());
            Object holder = redisTemplate.opsForValue().get(redisConfig.getHolderPrefix() + devicePo.getSerial());

            // holder 为 JSON，展示只取 username（兼容旧纯字符串）
            String holderName = null;
            if (holder != null) {
                try {
                    holderName = JSONObject.parseObject(holder.toString()).getString("username");
                } catch (Exception e) {
                    holderName = holder.toString();
                }
            }

            // 可用 = 在线且未被占用
            deviceVO.setStatus((status != null && (int)status == 1 && holder == null) ? BoolStatus.TRUE : BoolStatus.FALSE);
            deviceVO.setHolder(holderName);
            deviceVOS.add(deviceVO);
        }

        devicePoPageResult.setList(deviceVOS);
        return devicePoPageResult;
    }

    @Override
    public DevicePO detailById(Integer id) {
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

        String serial = devicePo.getSerial();
        String key = redisConfig.getHolderPrefix() + serial;

        if (deviceHoldDTO.getHolder() != null) {
            // 占用：原子写入，先到先得。cast=true 带 10s TTL 靠心跳续约，cast=false 不设 TTL
            JSONObject value = new JSONObject();
            value.put("username", deviceHoldDTO.getHolder());
            value.put("sessionId", deviceHoldDTO.getSessionId());
            value.put("cast", deviceHoldDTO.isCast());
            boolean ok = deviceHoldDTO.isCast()
                    ? Boolean.TRUE.equals(redisTemplate.opsForValue()
                        .setIfAbsent(key, value.toJSONString(), 10, TimeUnit.SECONDS))
                    : Boolean.TRUE.equals(redisTemplate.opsForValue()
                        .setIfAbsent(key, value.toJSONString()));
            if (ok) {
                // 抢占成功：清理可能残留的旧连接信息
                redisTemplate.delete(redisConfig.getConnectionPrefix() + serial);

                // 通知 agent 启动代理（cast 决定是否投屏）
                JSONObject cmd = new JSONObject();
                cmd.put("type", "start_proxy");
                cmd.put("serial", serial);
                cmd.put("cast", deviceHoldDTO.isCast());
                boolean delivered = DeviceSyncHandler.sendToDevice(serial, cmd.toJSONString());
                if (!delivered) {
                    // agent 会话不可达：回滚占用
                    redisTemplate.delete(key);
                    log.warn("start_proxy 未能下发（agent 会话不可达），已回滚占用: serial={}", serial);
                    return false;
                }
            }
            return ok;
        }

        // 释放：仅当会话匹配才删除
        Object current = redisTemplate.opsForValue().get(key);
        if (current == null) return true;

        String storedSessionId = null;
        try {
            storedSessionId = JSONObject.parseObject(current.toString()).getString("sessionId");
        } catch (Exception e) {
            // 旧格式/非 JSON 值：按可清理处理
        }

        if (storedSessionId == null || storedSessionId.equals(deviceHoldDTO.getSessionId())) {
            redisTemplate.delete(key);
            // 通知 agent 停止代理并清理连接信息
            JSONObject cmd = new JSONObject();
            cmd.put("type", "stop_proxy");
            cmd.put("serial", serial);
            DeviceSyncHandler.sendToDevice(serial, cmd.toJSONString());
            redisTemplate.delete(redisConfig.getConnectionPrefix() + serial);
        }
        return true;
    }

    @Override
    public JSONObject getConnectionById(Integer id) {
        // 按 id 解析 serial 后读 Redis(dc_<serial>) 连接信息，不存在返回 null
        DevicePO devicePo = this.getById(id);
        if (devicePo == null) return null;

        Object conn = redisTemplate.opsForValue().get(redisConfig.getConnectionPrefix() + devicePo.getSerial());
        if (conn == null) return null;
        try {
            return JSONObject.parseObject(conn.toString());
        } catch (Exception e) {
            return null;
        }
    }
}
