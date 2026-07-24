package com.seeker.tms.biz.testgen.service.impl;

import com.alibaba.fastjson.JSON;
import com.seeker.tms.biz.testgen.entities.OutlineVO;
import com.seeker.tms.biz.testgen.entities.XMindNode;
import lombok.AllArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用例生成任务的共享运行时状态：任务锁、生成中的内存树、正在生成用例的目标节点集合，
 * 以及 XMind 树 / 大纲的 Redis 读写。作为独立单例 bean 供编排类与 CaseWorkflow 共享，
 * 避免二者互相注入形成循环依赖。
 */
@Component
@AllArgsConstructor
public class TestGenStore {

    private static final String REDIS_KEY_XMIND = "testgen:task:%d:xmind";
    private static final String REDIS_KEY_OUTLINE = "testgen:task:%d:outline";

    /** 内存缓存：存储正在生成的任务的 XMind 树（生成期间的权威可变副本） */
    private static final Map<Integer, XMindNode> generatingTasks = new ConcurrentHashMap<>();

    /** 跟踪正在生成用例的目标节点（taskId -> Set<nodeId>），供前端恢复 loading 态 */
    private static final Map<Integer, Set<String>> generatingNodeIds = new ConcurrentHashMap<>();

    /** 任务级别的锁，防止并发修改同一任务的树 */
    private static final Map<Integer, Object> taskLocks = new ConcurrentHashMap<>();

    private final StringRedisTemplate redisTemplate;

    public Object getLock(Integer taskId) {
        return taskLocks.computeIfAbsent(taskId, k -> new Object());
    }

    // ---- XMind 树 ----

    /** 优先内存（生成中），否则读 Redis（已生成完成） */
    public XMindNode getTree(Integer taskId) {
        XMindNode generating = generatingTasks.get(taskId);
        if (generating != null) return generating;

        String json = redisTemplate.opsForValue().get(String.format(REDIS_KEY_XMIND, taskId));
        if (json == null || json.isBlank()) return null;
        return JSON.parseObject(json, XMindNode.class);
    }

    /** 写 Redis（不设过期：编辑态数据仅在完成导出 / 删除任务时主动清理）。生成中的内存树为原地改动，无需重复 put。 */
    public void saveTree(Integer taskId, XMindNode root) {
        redisTemplate.opsForValue().set(String.format(REDIS_KEY_XMIND, taskId), JSON.toJSONString(root));
    }

    /** 进入生成态：把内存树作为权威可变副本登记，并落一份 Redis 备份 */
    public void startGenerating(Integer taskId, XMindNode root) {
        generatingTasks.put(taskId, root);
        saveTree(taskId, root);
    }

    /** 退出生成态：移除内存树，后续读取回落 Redis */
    public void endGenerating(Integer taskId) {
        generatingTasks.remove(taskId);
    }

    // ---- 大纲 ----

    public OutlineVO getOutline(Integer taskId) {
        String json = redisTemplate.opsForValue().get(String.format(REDIS_KEY_OUTLINE, taskId));
        if (json == null || json.isBlank()) return null;
        return JSON.parseObject(json, OutlineVO.class);
    }

    public void saveOutline(Integer taskId, OutlineVO outline) {
        redisTemplate.opsForValue().set(String.format(REDIS_KEY_OUTLINE, taskId), JSON.toJSONString(outline));
    }

    // ---- 生成中的目标节点集合 ----

    public void addGeneratingNode(Integer taskId, String nodeId) {
        generatingNodeIds.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet()).add(nodeId);
    }

    public void removeGeneratingNode(Integer taskId, String nodeId) {
        Set<String> ids = generatingNodeIds.get(taskId);
        if (ids != null) {
            ids.remove(nodeId);
            if (ids.isEmpty()) generatingNodeIds.remove(taskId);
        }
    }

    public List<String> generatingNodeIds(Integer taskId) {
        Set<String> ids = generatingNodeIds.get(taskId);
        return ids != null ? new java.util.ArrayList<>(ids) : List.of();
    }

    // ---- 清理 ----

    /** 清理该任务的全部内存态与 Redis 树/大纲缓存，用于 完成/重新生成/删除 */
    public void clearState(Integer taskId) {
        generatingTasks.remove(taskId);
        generatingNodeIds.remove(taskId);
        taskLocks.remove(taskId);
        redisTemplate.delete(List.of(
                String.format(REDIS_KEY_XMIND, taskId),
                String.format(REDIS_KEY_OUTLINE, taskId)));
    }
}
