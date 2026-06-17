package com.seeker.tms.biz.confdiff.entities.compare;

import lombok.Data;

/**
 * 配置对比结果。按三大模块组织:目录对比、文件对比、文件内容对比。
 * 所有差异均以基准侧(A)为基准,描述目标侧(B)的缺少/多出。
 */
@Data
public class CompareResultVO {

    /** 执行中 */
    public static final String RUNNING = "RUNNING";
    /** 成功 */
    public static final String SUCCESS = "SUCCESS";
    /** 失败 */
    public static final String FAILED = "FAILED";

    /** 对比结果ID(用于历史回看) */
    private String id;
    /** 执行状态:RUNNING/SUCCESS/FAILED */
    private String status;
    /** 失败原因(status=FAILED 时) */
    private String message;
    private Integer projectId;
    private String projectName;
    /** 基准侧展示串 */
    private String refA;
    /** 目标侧展示串 */
    private String refB;
    /** refA 实际检出的提交完整 hash */
    private String resolvedCommitA;
    /** refB 实际检出的提交完整 hash */
    private String resolvedCommitB;

    /** 三类差异是否全部一致 */
    private boolean consistent;

    /** 模块一:目录对比 */
    private DirCompare dirCompare = new DirCompare();
    /** 模块二:文件对比 */
    private FileCompare fileCompare = new FileCompare();
    /** 模块三:文件内容对比 */
    private ContentCompare contentCompare = new ContentCompare();

    /** 不一致时生成的报告文件下载链接(预签名,可能过期,回看时会刷新) */
    private String reportUrl;
    /** 报告文件强制下载(另存为)链接 */
    private String reportDownloadUrl;
    /** 报告文件在 MinIO 的对象 key(用于回看时重新生成下载链接) */
    private String reportKey;
    /** 耗时(毫秒) */
    private long elapsedMs;
    /** 对比时间 */
    private String createTime;
}
