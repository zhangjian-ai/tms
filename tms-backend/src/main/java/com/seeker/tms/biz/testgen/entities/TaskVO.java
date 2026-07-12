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

    @ApiModelProperty("关联文档文件名(换行拼接,仅UPLOAD)")
    private String relatedNames;

    @ApiModelProperty("需求文档展示名(LINK来源为文档真实标题)")
    private String prdDisplayName;

    @ApiModelProperty("需求来源: UPLOAD/LINK")
    private String prdSource;

    @ApiModelProperty("是否解析文档内图片")
    private Boolean parseImage;

    @ApiModelProperty("是否解析二级文档(仅LINK)")
    private Boolean parseSubDoc;

    @ApiModelProperty("状态")
    private String status;

    @ApiModelProperty("消息")
    private String message;

    @ApiModelProperty("XMind文件名")
    private String xmindFileName;

    @ApiModelProperty("创建时间")
    private LocalDateTime createTime;
}
