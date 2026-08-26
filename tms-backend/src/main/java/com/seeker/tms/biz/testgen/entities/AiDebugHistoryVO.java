package com.seeker.tms.biz.testgen.entities;

import lombok.Data;

/**
 * AI 调试 - 历史记录：仅保存用户填写的系统/用户提示词与时间，按 用户+任务 维度存 Redis（最多 30 条）。
 */
@Data
public class AiDebugHistoryVO {
    private String systemPrompt;
    private String userPrompt;
    /** 记录时间（yyyy-MM-dd HH:mm:ss） */
    private String time;
}
