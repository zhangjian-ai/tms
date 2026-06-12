package com.seeker.tms.biz.confdiff.support;

import com.alibaba.fastjson.JSON;
import com.seeker.tms.biz.confdiff.entities.compare.CompareHistoryVO;
import com.seeker.tms.biz.confdiff.entities.compare.CompareResultVO;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 对比结果历史存储(Redis)。
 * - confdiff:result:{id}   完整结果 JSON
 * - confdiff:summary:{id}  摘要 JSON(列表项,便于按 id 原地更新状态)
 * - confdiff:history:{projectId}  该项目最近 N 个对比 id 列表
 * 异步对比先 create(RUNNING),完成后 update(SUCCESS/FAILED),同一 id 不重复入列。
 */
@Slf4j
@Component
@AllArgsConstructor
public class ConfCompareHistoryStore {

    private static final String RESULT_PREFIX = "confdiff:result:";
    private static final String SUMMARY_PREFIX = "confdiff:summary:";
    private static final String HISTORY_PREFIX = "confdiff:history:";
    /** 每个项目保留的历史条数 */
    private static final long MAX_HISTORY = 30;
    /** 保留时长(天) */
    private static final long TTL_DAYS = 7;

    private final StringRedisTemplate stringRedisTemplate;

    /** 创建一条记录(入列),用于异步对比触发时落地 RUNNING 占位 */
    public void create(CompareResultVO result) {
        try {
            writeResultAndSummary(result);
            String historyKey = HISTORY_PREFIX + result.getProjectId();
            stringRedisTemplate.opsForList().leftPush(historyKey, result.getId());
            stringRedisTemplate.opsForList().trim(historyKey, 0, MAX_HISTORY - 1);
            stringRedisTemplate.expire(historyKey, TTL_DAYS, TimeUnit.DAYS);
        } catch (Exception e) {
            log.error("创建对比历史失败", e);
        }
    }

    /** 更新已存在记录(不改变列表顺序),用于对比完成/失败后回写 */
    public void update(CompareResultVO result) {
        try {
            writeResultAndSummary(result);
        } catch (Exception e) {
            log.error("更新对比历史失败", e);
        }
    }

    private void writeResultAndSummary(CompareResultVO result) {
        stringRedisTemplate.opsForValue().set(RESULT_PREFIX + result.getId(),
                JSON.toJSONString(result), TTL_DAYS, TimeUnit.DAYS);
        CompareHistoryVO summary = new CompareHistoryVO(result.getId(), result.getProjectId(),
                result.getRefA(), result.getRefB(), result.isConsistent(),
                result.getStatus(), result.getMessage(), result.getCreateTime());
        stringRedisTemplate.opsForValue().set(SUMMARY_PREFIX + result.getId(),
                JSON.toJSONString(summary), TTL_DAYS, TimeUnit.DAYS);
    }

    public List<CompareHistoryVO> listByProject(Integer projectId) {
        List<String> ids = stringRedisTemplate.opsForList().range(HISTORY_PREFIX + projectId, 0, -1);
        List<CompareHistoryVO> list = new ArrayList<>();
        if (ids != null) {
            for (String id : ids) {
                String json = stringRedisTemplate.opsForValue().get(SUMMARY_PREFIX + id);
                if (json != null) {
                    list.add(JSON.parseObject(json, CompareHistoryVO.class));
                }
            }
        }
        return list;
    }

    public CompareResultVO get(String id) {
        String json = stringRedisTemplate.opsForValue().get(RESULT_PREFIX + id);
        return json == null ? null : JSON.parseObject(json, CompareResultVO.class);
    }
}
