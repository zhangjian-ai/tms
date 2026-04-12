package com.seeker.tms.websocket;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.seeker.tms.biz.device.entities.DeviceConnectionPO;
import com.seeker.tms.biz.device.entities.DevicePO;
import com.seeker.tms.common.config.RedisConfig;
import com.seeker.tms.common.enums.DeviceSys;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.annotation.Resource;
import java.time.LocalDateTime;

@Component
public class DeviceSyncHandler extends TextWebSocketHandler {

    @Resource
    private RedisTemplate<String, Integer> redisTemplate;

    @Resource
    private RedisConfig redisConfig;

    /**
     * 连接建立成功后的钩子
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String message = "OKAY";
        session.sendMessage(new TextMessage(message));
    }

    /**
     * 处理客户端消息
     */
    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
        // 消息体
        JSONObject payload = JSON.parseObject((String) message.getPayload());

        // 根据消息类型处理
        String type = payload.getString("type");
        String serial = payload.getString("serial");

        // 查询一下是否是已有设备
        DevicePO devicePo;

        switch (type) {
            case "status":
                String status = payload.getString("status");
                if (status == null) break;

                // 设置设备状态
                redisTemplate.opsForValue().set(redisConfig.getStatusPrefix() + serial, status.equals("online") ? 1 : 0);

                // 设备下线后就删除掉设备连接信息
                devicePo = Db.lambdaQuery(DevicePO.class).eq(DevicePO::getSerial, serial).one();

                if (devicePo != null && status.equals("offline")) {
                    Db.lambdaUpdate(DeviceConnectionPO.class)
                            .eq(DeviceConnectionPO::getDeviceId, devicePo.getId())
                            .remove();
                }

                break;
            case "device_info":
                JSONObject device_info = payload.getObject("device_info", JSONObject.class);
                if (device_info == null) break;

                // 查询一下是否是已有设备
                devicePo = Db.lambdaQuery(DevicePO.class).eq(DevicePO::getSerial, serial).one();

                if (devicePo == null) {
                    DevicePO newDevice = new DevicePO();
                    newDevice.setName(device_info.getString("name"));
                    newDevice.setSerial(device_info.getString("serial"));
                    newDevice.setBrand(device_info.getString("brand"));
                    newDevice.setModel(device_info.getString("model"));
                    newDevice.setDeviceSys(device_info.getString("device_sys").equals("android") ? DeviceSys.Android :
                            (device_info.getString("device_sys").equals("ios") ? DeviceSys.IOS : DeviceSys.Harmony));
                    newDevice.setOsVersion(device_info.getString("os_version"));
                    newDevice.setWidth(device_info.getShort("width"));
                    newDevice.setHeight(device_info.getShort("height"));

                    newDevice.setCreateTime(LocalDateTime.now());
                    newDevice.setUpdateTime(LocalDateTime.now());
                    Db.save(newDevice);
                } else {
                    // 设备信息只有系统版本可能发生变化，直接更新
                    Db.lambdaUpdate(DevicePO.class).eq(DevicePO::getSerial, serial)
                            .set(StrUtil.isNotBlank(device_info.getString("os_version")),
                                    DevicePO::getOsVersion, device_info.getString("os_version"))
                            .update();
                }

                break;
            case "connection_info":
                JSONObject connection_info = payload.getObject("connection_info", JSONObject.class);
                if (connection_info == null) break;

                // 查询一下是否是已有设备
                devicePo = Db.lambdaQuery(DevicePO.class).eq(DevicePO::getSerial, serial).one();

                if (devicePo != null) {
                    DeviceConnectionPO deviceConnectionPo = Db.lambdaQuery(DeviceConnectionPO.class).eq(DeviceConnectionPO::getDeviceId, devicePo.getId()).one();

                    if (deviceConnectionPo == null) {
                        DeviceConnectionPO newDeviceConnectionPO = BeanUtil.copyProperties(connection_info, DeviceConnectionPO.class);
                        newDeviceConnectionPO.setDeviceId(devicePo.getId());
                        newDeviceConnectionPO.setCreateTime(LocalDateTime.now());
                        newDeviceConnectionPO.setUpdateTime(LocalDateTime.now());
                        Db.save(newDeviceConnectionPO);
                    } else {
                        if (!devicePo.getDeviceSys().equals(DeviceSys.Harmony)) {
                            Db.lambdaUpdate(DeviceConnectionPO.class).eq(DeviceConnectionPO::getDeviceId, devicePo.getId())
                                    .set(StrUtil.isNotBlank(connection_info.getString("adb_host")), DeviceConnectionPO::getAdbHost, connection_info.getString("adb_host"))
                                    .set(StrUtil.isNotBlank(connection_info.getString("adb_port")), DeviceConnectionPO::getAdbPort, connection_info.getString("adb_port"))
                                    .set(StrUtil.isNotBlank(connection_info.getString("proxy_host")), DeviceConnectionPO::getProxyHost, connection_info.getString("proxy_host"))
                                    .set(StrUtil.isNotBlank(connection_info.getString("proxy_port")), DeviceConnectionPO::getProxyPort, connection_info.getString("proxy_port"))
                                    .set(StrUtil.isNotBlank(connection_info.getString("connection")), DeviceConnectionPO::getConnection, connection_info.getString("connection"))
                                    .update();
                        } else {
                            System.out.println("harmony 逻辑");
                        }
                    }
                }
                break;
        }
    }
}
