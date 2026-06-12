package com.seeker.tms.biz.confdiff.entities;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ConfProjectVO {
    private Integer id;
    private Integer machineId;

    @ApiModelProperty("所属机器名称")
    private String machineName;

    private String name;
    private String repoUrl;

    @ApiModelProperty("配置文件路径列表")
    private List<String> configPaths;

    private String defaultBranch;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
