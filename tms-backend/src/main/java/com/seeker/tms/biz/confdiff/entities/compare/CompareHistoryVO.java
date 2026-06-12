package com.seeker.tms.biz.confdiff.entities.compare;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对比历史摘要(用于历史列表)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CompareHistoryVO {
    private String id;
    private Integer projectId;
    private String refA;
    private String refB;
    private boolean consistent;
    /** 执行状态:RUNNING/SUCCESS/FAILED */
    private String status;
    /** 失败原因 */
    private String message;
    private String createTime;
}
