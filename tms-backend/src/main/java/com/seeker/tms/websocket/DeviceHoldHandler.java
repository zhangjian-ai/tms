package com.seeker.tms.websocket;

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

        // 设置设备持有者
        redisTemplate.opsForValue().setIfPresent(redisConfig.getHolderPrefix() + serial, username, 10, TimeUnit.SECONDS);
    }
}
