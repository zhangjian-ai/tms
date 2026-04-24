package com.seeker.tms.biz.testgen.websocket;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.seeker.tms.biz.testgen.entities.XMindNode;
import com.seeker.tms.biz.testgen.service.AgentChatService;
import com.seeker.tms.biz.testgen.service.TestGenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class TestGenWebSocketHandler extends TextWebSocketHandler {

    private static final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Resource
    private AgentChatService agentChatService;

    @Resource
    private TestGenService testGenService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String taskId = extractTaskId(session);
        if (taskId != null) {
            sessions.put(taskId, session);
            sendMessage(taskId, "CONNECTED", "连接成功");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String taskId = extractTaskId(session);
        if (taskId != null) {
            sessions.remove(taskId);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String taskId = extractTaskId(session);
        if (taskId == null) return;

        JSONObject payload = JSON.parseObject(message.getPayload());
        String type = payload.getString("type");

        if ("CHAT_MESSAGE".equals(type)) {
            String userMessage = payload.getString("message");
            XMindNode treeData = payload.getObject("treeData", XMindNode.class);
            if (treeData == null) {
                treeData = testGenService.getXMindData(Integer.parseInt(taskId));
            }
            agentChatService.chat(Integer.parseInt(taskId), userMessage, treeData);
        }
    }

    private String extractTaskId(WebSocketSession session) {
        String uri = session.getUri().toString();
        String[] parts = uri.split("/");
        return parts.length > 0 ? parts[parts.length - 1] : null;
    }

    public static void sendMessage(String taskId, String type, Object data) {
        WebSocketSession session = sessions.get(taskId);
        if (session != null && session.isOpen()) {
            try {
                Map<String, Object> msg = Map.of("type", type, "data", data);
                session.sendMessage(new TextMessage(JSON.toJSONString(msg)));
            } catch (IOException e) {
                log.error("发送 WebSocket 消息失败，任务ID: {}", taskId, e);
            }
        }
    }

    public static void sendProgress(String taskId, String message) {
        sendMessage(taskId, "PROGRESS", Map.of("message", message));
    }

    public static void sendTaskStatus(String taskId, String status, String message) {
        sendMessage(taskId, "TASK_STATUS", Map.of("status", status, "message", message));
    }

    public static void sendPointsGenerated(String taskId, Object testPoints) {
        sendMessage(taskId, "POINTS_GENERATED", testPoints);
    }

    public static void sendCasesGenerated(String taskId, Object testCases) {
        sendMessage(taskId, "CASES_GENERATED", testCases);
    }

    public static void sendError(String taskId, String error) {
        sendMessage(taskId, "ERROR", Map.of("error", error));
    }

    public static void sendChatResponse(String taskId, Object chatResponse) {
        sendMessage(taskId, "CHAT_RESPONSE", chatResponse);
    }

    public static void sendPointCasesGenerated(String taskId, String pointId, Object cases, boolean done) {
        sendMessage(taskId, "POINT_CASES_GENERATED", Map.of("pointId", pointId, "cases", cases, "done", done));
    }
}
