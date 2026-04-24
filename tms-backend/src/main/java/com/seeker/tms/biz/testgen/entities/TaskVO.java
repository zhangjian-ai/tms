package com.seeker.tms.biz.testgen.entities;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@ApiModel("任务信息响应")
public class TaskVO {
    @ApiModelProperty("任务ID")
    private Integer id;

    @ApiModelProperty("需求文档名称")
    private String prdName;

    @ApiModelProperty("需求类型")
    private String prdType;

    @ApiModelProperty("状态")
    private String status;

    @ApiModelProperty("消息")
    private String message;

    @ApiModelProperty("XMind文件名")
    private String xmindFileName;

    @ApiModelProperty("创建时间")
    private LocalDateTime createTime;
}
