package com.seeker.tms.biz.device.task;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.seeker.tms.biz.device.entities.DevicePO;
import com.seeker.tms.biz.device.websocket.DeviceSyncHandler;
import com.seeker.tms.common.config.RedisConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

/**
 * 设备代理对账：周期性检查存在连接键但占用键已不存在的设备，通知 agent 停代理并清理连接键。
 * 以 device 表为枚举源，逐 serial 用 hasKey 复核 Redis。
 */
@Slf4j
@Component
public class DeviceProxyReconciler {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Resource
    private RedisConfig redisConfig;

    @Scheduled(fixedDelay = 10_000, initialDelay = 15_000)
    public void reconcile() {
        try {
            List<DevicePO> devices = Db.list(DevicePO.class);
            if (devices == null || devices.isEmpty()) return;

            for (DevicePO device : devices) {
                String serial = device.getSerial();
                String connKey = redisConfig.getConnectionPrefix() + serial;
                // 无连接键 = 代理未运行
                if (!Boolean.TRUE.equals(redisTemplate.hasKey(connKey))) continue;

                String holderKey = redisConfig.getHolderPrefix() + serial;
                if (Boolean.TRUE.equals(redisTemplate.hasKey(holderKey))) continue; // 仍被占用

                // 占用已失效：动作前再复检一次占用键
                if (Boolean.TRUE.equals(redisTemplate.hasKey(holderKey))) continue;

                JSONObject cmd = new JSONObject();
                cmd.put("type", "stop_proxy");
                cmd.put("serial", serial);
                DeviceSyncHandler.sendToDevice(serial, cmd.toJSONString());

                redisTemplate.delete(connKey);
                log.info("对账回收：设备 {} 占用已失效，已通知停代理并清理连接信息", serial);
            }
        } catch (Exception e) {
            log.error("设备代理对账失败: {}", e.toString());
        }
    }
}
