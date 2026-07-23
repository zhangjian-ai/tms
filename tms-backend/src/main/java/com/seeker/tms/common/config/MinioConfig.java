package com.seeker.tms.common.config;

import io.minio.MinioClient;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "minio")
@EnableConfigurationProperties(MinioConfig.class)
public class MinioConfig {
    private String endpoint;
    private String accessKey;
    private String secretKey;
    private String bucketName;
    private Integer expire;

    /**
     * 预签名 URL 对外暴露的路径前缀（配 nginx 反代）：非空则把 URL 改写为 https://<endpoint-host><prefix>/...，
     * 为空则返回原始预签名 URL。默认 /minio。
     */
    private String publicPathPrefix = "/minio";

    // 注入客户端实例
    @Bean
    public MinioClient minioClient(){
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
