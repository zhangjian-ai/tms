package com.seeker.tms.biz.testgen.service;

import com.seeker.tms.biz.testgen.entities.XMindNode;

import java.util.List;
import java.util.Map;

public interface AgentChatService {
    void chat(Integer taskId, String userMessage, XMindNode currentTree);
    List<Map<String, String>> getChatHistory(Integer taskId);
}
