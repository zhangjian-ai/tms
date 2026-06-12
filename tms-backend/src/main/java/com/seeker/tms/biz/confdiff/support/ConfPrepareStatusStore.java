package com.seeker.tms.biz.confdiff.support;

import com.alibaba.fastjson.JSON;
import com.seeker.tms.biz.confdiff.entities.PrepareStatusVO;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 项目准备状态存储(Redis,过程数据)。值以 JSON 字符串保存,避免序列化器差异。
 */
@Slf4j
@Component
@AllArgsConstructor
public class ConfPrepareStatusStore {

    private static final String KEY_PREFIX = "confdiff:prepare:";
    /** 状态保留时长 */
    private static final long TTL_HOURS = 24;

    private final StringRedisTemplate stringRedisTemplate;

    public PrepareStatusVO get(Integer projectId) {
        String json = stringRedisTemplate.opsForValue().get(KEY_PREFIX + projectId);
        return json == null ? null : JSON.parseObject(json, PrepareStatusVO.class);
    }

    public void set(Integer projectId, String status, String message) {
        PrepareStatusVO vo = new PrepareStatusVO(projectId, status, message);
        stringRedisTemplate.opsForValue().set(KEY_PREFIX + projectId, JSON.toJSONString(vo), TTL_HOURS, TimeUnit.HOURS);
    }
}
