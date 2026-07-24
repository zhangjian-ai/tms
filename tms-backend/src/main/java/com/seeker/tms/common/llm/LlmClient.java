package com.seeker.tms.common.llm;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * 通用 LLM 流式客户端：基于 langchain4j 的 OpenAiStreamingChatModel，
 * 提供「流式取全文」「流式增量提取 JSON 对象」两种调用方式，以及大模型输出 JSON 的容错修复。
 * 与具体业务解耦——连接信息由调用方通过 {@link Conn} 传入。
 */
@Slf4j
@Component
public class LlmClient {

    /** LLM 连接三元组（baseUrl / apiKey / model），由调用方从各自的模型配置构造。 */
    @Getter
    @AllArgsConstructor
    public static class Conn {
        private final String baseUrl;
        private final String apiKey;
        private final String model;
    }

    private OpenAiStreamingChatModel buildModel(Conn conn, int timeoutSec, double temperature) {
        return OpenAiStreamingChatModel.builder()
                .apiKey(conn.getApiKey())
                .baseUrl(conn.getBaseUrl())
                .modelName(conn.getModel())
                .timeout(Duration.ofSeconds(timeoutSec))
                .temperature(temperature)
                .build();
    }

    /**
     * 流式调用取完整文本：把所有 token 拼成最终字符串返回（不做任何 JSON 修复）。
     * 需要按 JSON 解析的调用方，请自行用 {@link #extractFirstJsonObject(String)}。
     */
    public String streamToString(Conn conn, String system, String user, int timeoutSec, double temperature) {
        OpenAiStreamingChatModel model = buildModel(conn, timeoutSec, temperature);
        StringBuilder buffer = new StringBuilder();
        CompletableFuture<Void> future = new CompletableFuture<>();
        model.generate(
                List.of(SystemMessage.from(system), UserMessage.from(user)),
                new StreamingResponseHandler<>() {
                    @Override public void onNext(String token) { buffer.append(token); }
                    @Override public void onComplete(Response<AiMessage> response) { future.complete(null); }
                    @Override public void onError(Throwable error) { future.completeExceptionally(error); }
                }
        );
        try {
            future.get(timeoutSec, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("LLM 调用超时（>" + timeoutSec + "s，已接收 " + buffer.length() + " 字符）", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException("LLM 调用失败：" + describe(cause), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("LLM 调用被中断", e);
        }
        return buffer.toString();
    }

    /**
     * 流式调用，每解析出一个完整 JSON 对象就回调 onItem（用于边生成边落地）。
     * 阻塞等待流式结束；future 超时上限固定 600s。
     */
    public void streamJsonObjects(Conn conn, String system, String user,
                                  int timeoutSec, double temperature, Consumer<JSONObject> onItem) {
        OpenAiStreamingChatModel model = buildModel(conn, timeoutSec, temperature);
        StringBuilder buffer = new StringBuilder();
        CompletableFuture<Void> future = new CompletableFuture<>();
        model.generate(
                List.of(SystemMessage.from(system), UserMessage.from(user)),
                new StreamingResponseHandler<>() {
                    @Override public void onNext(String token) {
                        buffer.append(token);
                        extractJsonObjects(buffer, onItem);
                    }
                    @Override public void onComplete(Response<AiMessage> response) {
                        // 最后再尝试解析一次残留内容
                        extractJsonObjects(buffer, onItem);
                        future.complete(null);
                    }
                    @Override public void onError(Throwable error) {
                        future.completeExceptionally(error);
                    }
                }
        );
        try {
            future.get(600, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /**
     * 从模型回复中取首个完整 JSON 对象的文本切片（已修复内部未转义引号）；无合法对象返回 null。
     */
    public static String extractFirstJsonObject(String text) {
        if (text == null || text.isBlank()) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return repairJsonInnerQuotes(text.substring(start, end + 1));
    }

    /**
     * 从 buffer 中增量提取完整的 JSON 对象 {...}，每提取到一个就回调 onItem，并从 buffer 中移除已解析部分。
     */
    private void extractJsonObjects(StringBuilder buffer, Consumer<JSONObject> onItem) {
        String content = buffer.toString();
        int searchFrom = 0;

        while (searchFrom < content.length()) {
            int braceStart = content.indexOf('{', searchFrom);
            if (braceStart == -1) break;

            // 找到匹配的 }：跳过字符串内的花括号；对未转义的内部引号用结构符前瞻判断字符串边界
            int depth = 0;
            int braceEnd = -1;
            boolean inString = false;
            for (int i = braceStart; i < content.length(); i++) {
                char c = content.charAt(i);
                if (inString) {
                    if (c == '\\') {
                        i++;
                    } else if (c == '"' && isStructuralAfterQuote(content, i + 1)) {
                        inString = false;
                    }
                    continue;
                }
                if (c == '"') {
                    inString = true;
                } else if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        braceEnd = i;
                        break;
                    }
                }
            }

            if (braceEnd == -1) break; // 不完整，等待更多 token

            String jsonStr = content.substring(braceStart, braceEnd + 1);
            try {
                JSONObject obj = JSON.parseObject(repairJsonInnerQuotes(jsonStr));
                onItem.accept(obj);
                content = content.substring(braceEnd + 1);
                buffer.setLength(0);
                buffer.append(content);
                searchFrom = 0;
            } catch (Exception e) {
                // 解析失败，可能是嵌套 JSON 导致截断不对，跳过这个 { 继续找下一个
                searchFrom = braceStart + 1;
            }
        }
    }

    /**
     * 修复大模型输出里字符串值内部未转义的双引号（如 内容为 点击"确定"按钮 会破坏 JSON）。
     * 逐字符扫描：处于字符串内时遇到 "，若其后（跳过空白）不是结构符 : , } ] 或结尾，
     * 判定为内容引号并转义为 \"；否则视为字符串正常闭合。已转义的 \" 原样保留。
     */
    private static String repairJsonInnerQuotes(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder out = new StringBuilder(s.length() + 16);
        boolean inString = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!inString) {
                out.append(c);
                if (c == '"') inString = true;
                continue;
            }
            if (c == '\\') {
                out.append(c);
                if (i + 1 < s.length()) {
                    out.append(s.charAt(i + 1));
                    i++;
                }
                continue;
            }
            if (c == '"') {
                if (isStructuralAfterQuote(s, i + 1)) {
                    out.append(c);
                    inString = false;
                } else {
                    out.append("\\\"");
                }
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** 从 idx 起跳过空白后的首个字符是否为 JSON 结构符（: , } ]）或已到结尾 */
    private static boolean isStructuralAfterQuote(String s, int idx) {
        int i = idx;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        if (i >= s.length()) return true;
        char c = s.charAt(i);
        return c == ':' || c == ',' || c == '}' || c == ']';
    }

    private static String describe(Throwable e) {
        String msg = e.getMessage();
        return (msg != null && !msg.isBlank()) ? msg : e.getClass().getSimpleName();
    }
}
