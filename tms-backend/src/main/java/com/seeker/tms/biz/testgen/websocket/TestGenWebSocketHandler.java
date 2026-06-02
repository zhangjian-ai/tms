package com.seeker.tms.biz.testgen.websocket;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.seeker.tms.biz.testgen.entities.XMindNode;
import com.seeker.tms.biz.testgen.service.AgentChatService;
import com.seeker.tms.biz.testgen.service.TestGenService;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.annotation.Resource;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class TestGenWebSocketHandler extends TextWebSocketHandler {

    /** 任务占用者：taskId -> 当前持有该任务的用户 */
    private static final Map<String, TaskOwner> taskOwners = new ConcurrentHashMap<>();

    /**
     * 同一占用者下的全部活跃 session（支持多标签页）。
     * 存的是 ConcurrentWebSocketSessionDecorator —— Tomcat 的 WsRemoteEndpoint 在分帧写入大消息期间
     * （TEXT_PARTIAL_WRITING）不允许并发 sendMessage，会抛 IllegalStateException。
     * decorator 内部用锁 + 缓冲队列把同一 session 的发送串行化。
     */
    private static final Map<String, Set<WebSocketSession>> taskSessions = new ConcurrentHashMap<>();

    /** rawSession(connection 回调里的原始引用) -> decoratedSession，用于在 close 时定位要移除的对象 */
    private static final Map<WebSocketSession, WebSocketSession> sessionDecorators = new ConcurrentHashMap<>();

    /** decorator 的发送缓冲上限（字节），防止下游慢/卡时把内存撑爆。超过会断连。 */
    private static final int SEND_BUFFER_SIZE_LIMIT = 5 * 1024 * 1024;
    /** decorator 单次发送的等待上限（毫秒）。 */
    private static final int SEND_TIME_LIMIT_MS = 10_000;

    @Resource
    private AgentChatService agentChatService;

    @Resource
    private TestGenService testGenService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String taskId = extractTaskId(session);
        String username = (String) session.getAttributes().get(TestGenAuthHandshakeInterceptor.ATTR_USERNAME);
        if (taskId == null || username == null) {
            log.warn("WebSocket 缺少 taskId 或 username，关闭连接");
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        // 包一层并发安全的 decorator，串行化对同一 session 的发送
        WebSocketSession decorated = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, SEND_BUFFER_SIZE_LIMIT);

        // 在 owner 锁内同时完成"占用判定"和"加入 session 集合"，避免 close 与 establish 的竞态
        final String[] decision = new String[1];
        final TaskOwner[] currentOwner = new TaskOwner[1];
        taskOwners.compute(taskId, (k, existing) -> {
            if (existing == null) {
                TaskOwner created = new TaskOwner(username, Instant.now().toEpochMilli());
                taskSessions.computeIfAbsent(taskId, kk -> ConcurrentHashMap.newKeySet()).add(decorated);
                sessionDecorators.put(session, decorated);
                decision[0] = "GRANTED";
                currentOwner[0] = created;
                return created;
            }
            if (existing.getUsername().equals(username)) {
                existing.touch();
                taskSessions.computeIfAbsent(taskId, kk -> ConcurrentHashMap.newKeySet()).add(decorated);
                sessionDecorators.put(session, decorated);
                decision[0] = "SHARED";
                currentOwner[0] = existing;
                return existing;
            }
            decision[0] = "OCCUPIED";
            currentOwner[0] = existing;
            return existing;
        });

        if ("OCCUPIED".equals(decision[0])) {
            TaskOwner owner = currentOwner[0];
            try {
                Map<String, Object> payload = new HashMap<>();
                payload.put("ownership", "OCCUPIED");
                payload.put("occupiedBy", owner.getUsername());
                payload.put("acquiredAt", owner.getAcquiredAt());
                session.sendMessage(new TextMessage(JSON.toJSONString(Map.of("type", "OCCUPIED", "data", payload))));
            } catch (IOException ignored) {
            }
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("message", "连接成功");
        payload.put("ownership", decision[0]);
        sendToSession(decorated, "CONNECTED", payload);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String taskId = extractTaskId(session);
        if (taskId == null) return;

        WebSocketSession decorated = sessionDecorators.remove(session);

        // 在 owner 锁内同时移除 session 与判空清 owner，避免与 establish 的竞态
        taskOwners.compute(taskId, (k, existing) -> {
            Set<WebSocketSession> sessions = taskSessions.get(taskId);
            if (sessions != null) {
                if (decorated != null) sessions.remove(decorated);
                if (sessions.isEmpty()) {
                    taskSessions.remove(taskId);
                    return null; // 清空 owner
                }
            }
            return existing;
        });
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String taskId = extractTaskId(session);
        if (taskId == null) return;

        JSONObject payload = JSON.parseObject(message.getPayload());
        String type = payload.getString("type");

        if ("HEARTBEAT".equals(type)) {
            TaskOwner owner = taskOwners.get(taskId);
            if (owner != null) owner.touch();
            // 用 decorator 发，避免与广播线程并发写同一 session
            WebSocketSession decorated = sessionDecorators.getOrDefault(session, session);
            sendToSession(decorated, "HEARTBEAT_ACK", Map.of("ts", Instant.now().toEpochMilli()));
            return;
        }

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
        String path = session.getUri().getPath();
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : null;
    }

    // ---------- 静态广播 API（保持原签名兼容）----------

    public static void sendMessage(String taskId, String type, Object data) {
        Set<WebSocketSession> sessions = taskSessions.get(taskId);
        if (sessions == null || sessions.isEmpty()) return;
        String text = JSON.toJSONString(Map.of("type", type, "data", data));
        TextMessage msg = new TextMessage(text);
        for (WebSocketSession s : sessions) {
            if (!s.isOpen()) continue;
            try {
                s.sendMessage(msg);
            } catch (Exception e) {
                // decorator 缓冲超限 / 断连 等情况下抛 IOException 或 IllegalStateException：
                // 不再可用，主动剔除以避免后续重复失败
                log.warn("发送 WebSocket 消息失败，taskId={}, type={}, 移除 session: {}",
                        taskId, type, e.getMessage());
                sessions.remove(s);
                try { s.close(CloseStatus.SERVER_ERROR); } catch (Exception ignored) {}
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

    /** 流式新增单个测试点：携带新节点 id，前端可据此精确居中到刚加的节点 */
    public static void sendPointAdded(String taskId, Object root, String latestNodeId) {
        sendMessage(taskId, "POINT_ADDED",
                Map.of("root", root, "latestNodeId", latestNodeId == null ? "" : latestNodeId));
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

    /** 规划阶段：已生成大纲，等待用户确认 */
    public static void sendPlanDrafted(String taskId, Object outline) {
        sendMessage(taskId, "PLAN_DRAFTED", Map.of("outline", outline));
    }

    /** 阶段切换：phase 取值 PLANNING / EXTRACTING / REFINING / GENERATING_CASES / EDITING / DONE */
    public static void sendPhaseChanged(String taskId, String phase, String message) {
        sendMessage(taskId, "PHASE_CHANGED", Map.of("phase", phase, "message", message == null ? "" : message));
    }

    /**
     * 主动关闭该任务的全部 WebSocket 连接并清理占用者。
     * 由 finishTask / deleteTask 调用——任务已落终态，不再需要继续推送。
     * regenerateTask 不应调用此方法：发起者的 ws 仍需用于接收新一轮推送。
     */
    public static void closeAllSessions(String taskId) {
        Set<WebSocketSession> sessions = taskSessions.remove(taskId);
        taskOwners.remove(taskId);
        if (sessions == null) return;
        for (WebSocketSession s : new ArrayList<>(sessions)) {
            try {
                if (s.isOpen()) s.close(CloseStatus.NORMAL);
            } catch (IOException e) {
                log.warn("关闭 task session 失败，taskId={}", taskId, e);
            }
        }
    }

    private static void sendToSession(WebSocketSession session, String type, Object data) {
        if (session == null || !session.isOpen()) return;
        try {
            session.sendMessage(new TextMessage(JSON.toJSONString(Map.of("type", type, "data", data))));
        } catch (Exception e) {
            log.warn("发送 WebSocket 消息失败 type={}: {}", type, e.getMessage());
        }
    }

    @Getter
    private static class TaskOwner {
        private final String username;
        private final long acquiredAt;
        private volatile long lastHeartbeat;

        TaskOwner(String username, long acquiredAt) {
            this.username = username;
            this.acquiredAt = acquiredAt;
            this.lastHeartbeat = acquiredAt;
        }

        void touch() {
            this.lastHeartbeat = Instant.now().toEpochMilli();
        }
    }
}
