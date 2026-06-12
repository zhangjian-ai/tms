package com.seeker.tms.biz.confdiff.entities;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 对比的一侧:分支 + 该分支上的某个 commit(可选)
 */
@Data
public class CompareRef {

    @NotBlank(message = "分支名不能为空")
    @ApiModelProperty(value = "分支名", required = true)
    private String branch;

    @ApiModelProperty("该分支上的 commit hash,留空则取分支最新提交")
    private String commit;

    /** 展示用:branch 或 branch@commit */

    public String display() {
        return commit == null || commit.trim().isEmpty()
                ? branch
                : branch + "@" + commit.trim();
    }
}
