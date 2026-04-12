package com.seeker.tms.common.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.protocol.ProtocolVersion;
import lombok.Data;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;

import java.time.Duration;

@Data
@Configuration
@ConfigurationProperties(prefix = "redis.device")
public class RedisConfig {
    private String statusPrefix;
    private String holderPrefix;

    /**
     * 配置 Lettuce 客户端选项，解决连接超时问题
     */
    @Bean
    public LettuceClientConfigurationBuilderCustomizer lettuceClientConfigurationBuilderCustomizer() {
        return clientConfigurationBuilder -> {
            // Socket 配置
            SocketOptions socketOptions = SocketOptions.builder()
                    .keepAlive(true)  // 启用 TCP keepalive
                    .tcpNoDelay(true)  // 禁用 Nagle 算法，减少延迟
                    .connectTimeout(Duration.ofSeconds(10))  // 连接超时
                    .build();

            // 客户端选项
            ClientOptions clientOptions = ClientOptions.builder()
                    .socketOptions(socketOptions)
                    .autoReconnect(true)  // 自动重连
                    .pingBeforeActivateConnection(true)  // 激活连接前先 ping
                    .protocolVersion(ProtocolVersion.RESP3)  // 使用 RESP3 协议
                    .timeoutOptions(TimeoutOptions.enabled(Duration.ofSeconds(10)))  // 命令超时
                    .build();

            clientConfigurationBuilder.clientOptions(clientOptions);
        };
    }
}
