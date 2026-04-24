package com.seeker.tms.websocket;

import com.seeker.tms.biz.testgen.websocket.TestGenWebSocketHandler;
import lombok.AllArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@EnableWebSocket
@AllArgsConstructor
@Configuration
public class WebSocketConfig implements WebSocketConfigurer {

    private final DeviceSyncHandler deviceSyncHandler;

    private final DeviceHoldHandler deviceHoldHandler;

    private final TestGenWebSocketHandler testGenWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(deviceSyncHandler, "/ws/device/sync")
                .addHandler(deviceHoldHandler, "/ws/device/hold")
                .addHandler(testGenWebSocketHandler, "/ws/testgen/*")
                .setAllowedOrigins("*");
    }
}
