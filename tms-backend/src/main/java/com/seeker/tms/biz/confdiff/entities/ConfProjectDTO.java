package com.seeker.tms.biz.confdiff.entities;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.List;

@Data
public class ConfProjectDTO {

    @ApiModelProperty("项目ID,新增不传,编辑必传")
    private Integer id;

    @NotNull(message = "所属机器ID不能为空")
    @ApiModelProperty("所属机器ID")
    private Integer machineId;

    @NotBlank(message = "项目名称不能为空")
    @ApiModelProperty("项目名称(同时作为clone目录名)")
    private String name;

    @NotBlank(message = "仓库地址不能为空")
    @ApiModelProperty("git仓库地址")
    private String repoUrl;

    @NotEmpty(message = "配置路径不能为空")
    @ApiModelProperty("配置文件路径列表(相对仓库根)")
    private List<String> configPaths;

    @ApiModelProperty("默认分支,默认master")
    private String defaultBranch = "master";

    @ApiModelProperty("备注")
    private String remark;
}
