package com.seeker.tms.biz.device.websocket;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.seeker.tms.biz.device.entities.DevicePO;
import com.seeker.tms.common.config.RedisConfig;
import com.seeker.tms.common.enums.DeviceSys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class DeviceSyncHandler extends TextWebSocketHandler {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Resource
    private RedisConfig redisConfig;

    /** decorator 发送缓冲上限（字节）与单次发送等待上限（毫秒）。 */
    private static final int SEND_BUFFER_SIZE_LIMIT = 1024 * 1024;
    private static final int SEND_TIME_LIMIT_MS = 5_000;

    /** serial -> agent 会话（并发安全 decorator，供反向推送）。 */
    private static final Map<String, WebSocketSession> serialSessions = new ConcurrentHashMap<>();
    /** rawSession -> decorator，用于 close 时定位。 */
    private static final Map<WebSocketSession, WebSocketSession> sessionDecorators = new ConcurrentHashMap<>();
    /** rawSession -> 承载的 serial 集合。 */
    private static final Map<WebSocketSession, Set<String>> sessionSerials = new ConcurrentHashMap<>();

    /**
     * 向承载指定 serial 的 agent 会话反向推送指令。返回是否成功送达。
     */
    public static boolean sendToDevice(String serial, String jsonMessage) {
        WebSocketSession session = serialSessions.get(serial);
        if (session == null || !session.isOpen()) return false;
        try {
            session.sendMessage(new TextMessage(jsonMessage));
            return true;
        } catch (Exception e) {
            log.warn("向 agent 推送指令失败 serial={}: {}", serial, e.getMessage());
            return false;
        }
    }

    /**
     * agent 重连自愈：serial 首次出现在新会话时，若设备仍被占用则重新下发 start_proxy 恢复代理。
     */
    private void maybeResumeProxy(String serial) {
        try {
            Object holder = redisTemplate.opsForValue().get(redisConfig.getHolderPrefix() + serial);
            if (holder == null) return; // 未占用，无需恢复

            boolean cast = false;
            try {
                cast = JSONObject.parseObject(holder.toString()).getBooleanValue("cast");
            } catch (Exception ignore) {
                // 旧格式/非 JSON 值：按不投屏恢复
            }

            JSONObject cmd = new JSONObject();
            cmd.put("type", "start_proxy");
            cmd.put("serial", serial);
            cmd.put("cast", cast);
            sendToDevice(serial, cmd.toJSONString());
            log.info("agent 重连自愈：设备 {} 仍被占用，重新下发 start_proxy(cast={})", serial, cast);
        } catch (Exception e) {
            log.warn("自愈下发 start_proxy 失败 serial={}: {}", serial, e.getMessage());
        }
    }

    /**
     * 连接建立成功后的钩子
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // 包一层并发安全 decorator，串行化反向推送
        WebSocketSession decorated = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, SEND_BUFFER_SIZE_LIMIT);
        sessionDecorators.put(session, decorated);
        session.sendMessage(new TextMessage("OKAY"));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        WebSocketSession decorated = sessionDecorators.remove(session);
        Set<String> serials = sessionSerials.remove(session);
        if (serials != null) {
            for (String serial : serials) {
                // 仅当映射仍指向本会话时才移除
                serialSessions.remove(serial, decorated);
            }
        }
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

        // 登记 serial -> agent 会话，供反向推送路由
        if (serial != null) {
            WebSocketSession decorated = sessionDecorators.getOrDefault(session, session);
            serialSessions.put(serial, decorated);
            // add 返回 true 表示该 serial 首次出现在本会话上
            boolean firstOnSession = sessionSerials.computeIfAbsent(session, k -> ConcurrentHashMap.newKeySet()).add(serial);
            if (firstOnSession) {
                // agent 重连自愈：设备仍被占用则恢复代理
                maybeResumeProxy(serial);
            }
        }

        // 查询一下是否是已有设备
        DevicePO devicePo;

        switch (type) {
            case "status":
                String status = payload.getString("status");
                if (status == null) break;

                // 设置设备状态（带 15s TTL，过期后自动显示不可用）
                redisTemplate.opsForValue().set(redisConfig.getStatusPrefix() + serial, status.equals("online") ? 1 : 0, 15, TimeUnit.SECONDS);

                // 设备下线：释放占用并清理连接信息
                if (status.equals("offline")) {
                    redisTemplate.delete(redisConfig.getHolderPrefix() + serial);
                    redisTemplate.delete(redisConfig.getConnectionPrefix() + serial);
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
                    // 更新系统版本；宽高在 >0 时一并更新
                    Short w = device_info.getShort("width");
                    Short h = device_info.getShort("height");
                    Db.lambdaUpdate(DevicePO.class).eq(DevicePO::getSerial, serial)
                            .set(StrUtil.isNotBlank(device_info.getString("os_version")),
                                    DevicePO::getOsVersion, device_info.getString("os_version"))
                            .set(w != null && w > 0, DevicePO::getWidth, w)
                            .set(h != null && h > 0, DevicePO::getHeight, h)
                            .update();
                }

                break;
            case "connection_info":
                JSONObject connection_info = payload.getObject("connection_info", JSONObject.class);
                if (connection_info == null) break;

                // 写连接信息到 Redis(dc_<serial>)，snake_case 归一为 camelCase
                JSONObject conn = new JSONObject();
                conn.put("proxyHost", connection_info.getString("proxy_host"));
                conn.put("proxyPort", connection_info.getString("proxy_port"));
                conn.put("adbHost", connection_info.getString("adb_host"));
                conn.put("adbPort", connection_info.getString("adb_port"));
                conn.put("connection", connection_info.getString("connection"));
                redisTemplate.opsForValue().set(redisConfig.getConnectionPrefix() + serial, conn.toJSONString());
                break;
        }
    }
}
