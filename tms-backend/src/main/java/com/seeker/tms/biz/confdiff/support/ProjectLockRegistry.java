package com.seeker.tms.biz.confdiff.support;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 按项目的串行锁注册表。同一项目的远程 git 操作(clone/fetch/checkout)有状态(单一工作树),需串行。
 * 准备(clone)与对比共用同一把锁。
 */
@Component
public class ProjectLockRegistry {

    private final ConcurrentHashMap<Integer, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ReentrantLock get(Integer projectId) {
        return locks.computeIfAbsent(projectId, k -> new ReentrantLock());
    }
}
