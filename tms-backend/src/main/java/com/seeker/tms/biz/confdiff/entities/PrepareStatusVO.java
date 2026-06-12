package com.seeker.tms.biz.confdiff.entities;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 项目准备(首次 clone)状态
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PrepareStatusVO {

    /** 未准备(远程不存在,需 clone) */
    public static final String NOT_PREPARED = "NOT_PREPARED";
    /** 准备中(clone 进行中) */
    public static final String PREPARING = "PREPARING";
    /** 已就绪 */
    public static final String READY = "READY";
    /** 准备失败 */
    public static final String FAILED = "FAILED";

    private Integer projectId;
    private String status;
    private String message;
}
