package com.seeker.tms.biz.testgen.service.impl;

import com.alibaba.fastjson.JSON;
import com.seeker.tms.biz.testgen.agent.TestGenAgent;
import com.seeker.tms.biz.testgen.agent.XMindTreeTools;
import com.seeker.tms.biz.testgen.config.LlmProperties;
import com.seeker.tms.biz.testgen.entities.*;
import com.seeker.tms.biz.testgen.service.AgentChatService;
import com.seeker.tms.biz.testgen.utils.PromptLoader;
import com.seeker.tms.biz.testgen.websocket.TestGenWebSocketHandler;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@AllArgsConstructor
public class AgentChatServiceImpl implements AgentChatService {

    private static final String REDIS_KEY_XMIND = "testgen:task:%d:xmind";
    private static final String REDIS_KEY_CHAT = "testgen:task:%d:chat";
    private static final int REDIS_EXPIRE_HOURS = 72;
    private static final int MAX_CHAT_SIZE = 50;
    private static final int RECENT_ROUNDS = 5;

    private final LlmProperties llmProperties;
    private final StringRedisTemplate redisTemplate;

    @Override
    @Async("taskExecutor")
    public void chat(Integer taskId, String userMessage, XMindNode currentTree) {
        String wsKey = String.valueOf(taskId);
        try {
            XMindTreeTools tools = new XMindTreeTools(currentTree);

            LlmProperties.ModelConfig cfg = llmProperties.getThinking();
            OpenAiChatModel model = OpenAiChatModel.builder()
                    .apiKey(cfg.getApiKey())
                    .baseUrl(cfg.getBaseUrl())
                    .modelName(cfg.getModel())
                    .timeout(Duration.ofSeconds(120))
                    .build();

            MessageWindowChatMemory memory = MessageWindowChatMemory.withMaxMessages(20);
            loadChatMemory(taskId, memory);

            TestGenAgent agent = AiServices.builder(TestGenAgent.class)
                    .chatLanguageModel(model)
                    .chatMemory(memory)
                    .tools(tools)
                    .build();

            String contextMessage = "当前树结构JSON:\n" + JSON.toJSONString(currentTree)
                    + "\n\n用户消息: " + userMessage;

            String response = agent.chat(contextMessage);

            XMindNode updatedTree = tools.getRoot();
            List<String> changedIds = tools.getChangedNodeIds();

            // 保存到 Redis
            saveXMindToRedis(taskId, updatedTree);
            saveChatToRedis(taskId, "user", userMessage);
            saveChatToRedis(taskId, "assistant", response);

            // 检查是否需要摘要压缩
            compactOldRounds(taskId);

            // WebSocket 推送
            ChatResponseVO vo = new ChatResponseVO();
            vo.setMessage(response);
            vo.setTreeData(updatedTree);
            vo.setChangedNodeIds(changedIds);
            TestGenWebSocketHandler.sendChatResponse(wsKey, vo);

        } catch (Exception e) {
            log.error("Agent 对话失败，taskId={}", taskId, e);
            TestGenWebSocketHandler.sendError(wsKey, "对话失败: " + e.getMessage());
        }
    }

    @Override
    public List<Map<String, String>> getChatHistory(Integer taskId) {
        String key = String.format(REDIS_KEY_CHAT, taskId);
        List<String> raw = redisTemplate.opsForList().range(key, 0, -1);
        if (raw == null) return Collections.emptyList();
        List<Map<String, String>> result = new ArrayList<>();
        for (String json : raw) {
            result.add(JSON.parseObject(json, Map.class));
        }
        return result;
    }

    private void loadChatMemory(Integer taskId, MessageWindowChatMemory memory) {
        List<Map<String, String>> history = getChatHistory(taskId);

        // 分离摘要和普通消息
        List<Map<String, String>> summaries = new ArrayList<>();
        List<Map<String, String>> conversations = new ArrayList<>();
        for (Map<String, String> msg : history) {
            if ("summary".equals(msg.get("role"))) {
                summaries.add(msg);
            } else {
                conversations.add(msg);
            }
        }

        // 先注入摘要
        if (!summaries.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Map<String, String> s : summaries) sb.append(s.get("content")).append("\n");
            memory.add(UserMessage.from("[历史对话摘要]\n" + sb));
            memory.add(AiMessage.from("好的，我已了解之前的对话背景。"));
        }

        // 最近 5 轮保留原文
        int recentCount = Math.min(conversations.size(), RECENT_ROUNDS * 2);
        List<Map<String, String>> recent = conversations.subList(
                Math.max(0, conversations.size() - recentCount), conversations.size());
        for (Map<String, String> msg : recent) {
            if ("user".equals(msg.get("role"))) {
                memory.add(UserMessage.from(msg.get("content")));
            } else {
                memory.add(AiMessage.from(msg.get("content")));
            }
        }
    }

    private void compactOldRounds(Integer taskId) {
        List<Map<String, String>> history = getChatHistory(taskId);

        List<Map<String, String>> conversations = new ArrayList<>();
        for (Map<String, String> msg : history) {
            if (!"summary".equals(msg.get("role"))) conversations.add(msg);
        }

        int recentCount = RECENT_ROUNDS * 2;
        if (conversations.size() <= recentCount) return;

        List<Map<String, String>> oldMessages = conversations.subList(0, conversations.size() - recentCount);
        StringBuilder sb = new StringBuilder();
        for (Map<String, String> msg : oldMessages) {
            sb.append(msg.get("role")).append(": ").append(msg.get("content")).append("\n");
        }

        try {
            LlmProperties.ModelConfig cfg = llmProperties.getThinking();
            OpenAiChatModel model = OpenAiChatModel.builder()
                    .apiKey(cfg.getApiKey())
                    .baseUrl(cfg.getBaseUrl())
                    .modelName(cfg.getModel())
                    .timeout(Duration.ofSeconds(60))
                    .build();

            String summary = model.generate(PromptLoader.loadWithParams("chat_summary",
                    Map.of("history", sb.toString())));

            // 重建 Redis 列表：摘要 + 最近消息
            String key = String.format(REDIS_KEY_CHAT, taskId);
            redisTemplate.delete(key);

            Map<String, String> summaryMsg = Map.of("role", "summary", "content", summary);
            redisTemplate.opsForList().rightPush(key, JSON.toJSONString(summaryMsg));

            List<Map<String, String>> recentMessages = conversations.subList(
                    conversations.size() - recentCount, conversations.size());
            for (Map<String, String> msg : recentMessages) {
                redisTemplate.opsForList().rightPush(key, JSON.toJSONString(msg));
            }
            redisTemplate.expire(key, REDIS_EXPIRE_HOURS, TimeUnit.HOURS);

            log.info("任务 {} 已压缩 {} 条旧对话为摘要", taskId, oldMessages.size());
        } catch (Exception e) {
            log.warn("对话摘要压缩失败，跳过: {}", e.getMessage());
        }
    }

    private void saveXMindToRedis(Integer taskId, XMindNode tree) {
        String key = String.format(REDIS_KEY_XMIND, taskId);
        redisTemplate.opsForValue().set(key, JSON.toJSONString(tree), REDIS_EXPIRE_HOURS, TimeUnit.HOURS);
    }

    private void saveChatToRedis(Integer taskId, String role, String content) {
        String key = String.format(REDIS_KEY_CHAT, taskId);
        Map<String, String> msg = Map.of("role", role, "content", content);
        redisTemplate.opsForList().rightPush(key, JSON.toJSONString(msg));
        redisTemplate.opsForList().trim(key, -MAX_CHAT_SIZE, -1);
        redisTemplate.expire(key, REDIS_EXPIRE_HOURS, TimeUnit.HOURS);
    }
}
