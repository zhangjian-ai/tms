package com.seeker.tms.websocket;

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

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(deviceSyncHandler, "/ws/device/sync")
                .addHandler(deviceHoldHandler, "/ws/device/hold")
                .setAllowedOrigins("*");
    }
}
