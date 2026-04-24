package com.seeker.tms.biz.testgen.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "testgen")
public class LlmProperties {

    private ModelConfig thinking = new ModelConfig();
    private ModelConfig vision = new ModelConfig();

    @Data
    public static class ModelConfig {
        private String baseUrl;
        private String apiKey;
        private String model;
    }
}
