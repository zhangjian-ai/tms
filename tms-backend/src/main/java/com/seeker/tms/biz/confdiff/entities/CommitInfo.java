package com.seeker.tms.biz.confdiff.entities;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 提交信息(用于前端 commit 下拉选择)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommitInfo {
    /** 完整 hash */
    private String hash;
    /** 短 hash */
    private String shortHash;
    /** 提交说明 */
    private String message;
    /** 作者 */
    private String author;
    /** 提交时间 */
    private String date;
    /** 该提交对应的 tag(多个以逗号分隔,无则为空) */
    private String tag;
}
