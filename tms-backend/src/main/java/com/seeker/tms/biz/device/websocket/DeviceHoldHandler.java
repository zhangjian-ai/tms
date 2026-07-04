package com.seeker.tms.biz.device.websocket;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.seeker.tms.common.config.RedisConfig;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

@Component
public class DeviceHoldHandler extends TextWebSocketHandler {

    @Resource
    private RedisTemplate<String, String> redisTemplate;

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
        String serial = payload.getString("serial");
        String username = payload.getString("username");
        String sessionId = payload.getString("sessionId");

        String key = redisConfig.getHolderPrefix() + serial;

        // 按会话续约：仅当占用键存在且其 sessionId 与本心跳一致时才刷新，
        // 防止第二个会话的心跳误续或覆盖当前持有者
        String current = redisTemplate.opsForValue().get(key);
        if (current == null) {
            return;
        }

        String storedSessionId;
        try {
            storedSessionId = JSON.parseObject(current).getString("sessionId");
        } catch (Exception e) {
            storedSessionId = null;
        }

        if (storedSessionId != null && storedSessionId.equals(sessionId)) {
            JSONObject value = new JSONObject();
            value.put("username", username);
            value.put("sessionId", sessionId);
            redisTemplate.opsForValue().set(key, value.toJSONString(), 10, TimeUnit.SECONDS);
        }
    }
}
