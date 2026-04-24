package com.seeker.tms.biz.testgen.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class PromptLoader {

    private static final Map<String, String> PROMPT_CACHE = new HashMap<>();

    public static String load(String promptName) {
        if (PROMPT_CACHE.containsKey(promptName)) {
            return PROMPT_CACHE.get(promptName);
        }

        try {
            ClassPathResource resource = new ClassPathResource("prompts/" + promptName + ".txt");
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                String content = reader.lines().collect(Collectors.joining("\n"));
                PROMPT_CACHE.put(promptName, content);
                return content;
            }
        } catch (Exception e) {
            log.error("加载 Prompt 文件失败: {}", promptName, e);
            throw new RuntimeException("Prompt 文件加载失败: " + promptName, e);
        }
    }

    public static String loadWithParams(String promptName, Map<String, String> params) {
        String template = load(promptName);
        String result = template;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String placeholder = "{{" + entry.getKey() + "}}";
            result = result.replace(placeholder, entry.getValue() != null ? entry.getValue() : "");
        }
        // 处理条件块 {{#key}}...{{/key}}
        result = processConditionalBlocks(result, params);
        return result;
    }

    private static String processConditionalBlocks(String text, Map<String, String> params) {
        String result = text;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            String startTag = "{{#" + key + "}}";
            String endTag = "{{/" + key + "}}";

            int startIdx = result.indexOf(startTag);
            while (startIdx != -1) {
                int endIdx = result.indexOf(endTag, startIdx);
                if (endIdx == -1) break;

                String blockContent = result.substring(startIdx + startTag.length(), endIdx);
                String replacement = (entry.getValue() != null && !entry.getValue().isBlank())
                    ? blockContent
                    : "";

                result = result.substring(0, startIdx) + replacement + result.substring(endIdx + endTag.length());
                startIdx = result.indexOf(startTag);
            }
        }
        return result;
    }
}
