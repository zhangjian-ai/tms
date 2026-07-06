package com.seeker.tms.biz.testgen.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 模型运行时配置值对象:供构建 OpenAiChatModel / OpenAiStreamingChatModel 使用。
 * 字段方法名与原 LlmProperties.ModelConfig 保持一致(getBaseUrl/getApiKey/getModel),
 * 调用点无需改动 builder 代码。
 */
@Data
@AllArgsConstructor
public class ModelConfig {
    private String baseUrl;
    private String apiKey;
    private String model;
}
