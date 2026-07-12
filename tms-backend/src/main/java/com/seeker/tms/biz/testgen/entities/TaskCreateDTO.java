package com.seeker.tms.biz.testgen.entities;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.util.List;

@Data
@ApiModel("创建任务请求")
public class TaskCreateDTO {
    @ApiModelProperty(value = "需求来源: UPLOAD 上传 / LINK 文档链接", example = "LINK")
    private String prdSource;

    @ApiModelProperty(value = "主文档：UPLOAD 为主文件名（带后缀），LINK 为文档链接", required = true)
    @NotBlank(message = "需求文档名称不能为空")
    private String prdName;

    @ApiModelProperty(value = "关联文档文件名列表（仅 UPLOAD 来源）")
    private List<String> relatedNames;

    @ApiModelProperty(value = "是否解析文档内图片，默认否", example = "false")
    private Boolean parseImage;

    @ApiModelProperty(value = "是否解析二级文档（仅 LINK 来源）：提取主文档引用的飞书文档并抓取，默认否", example = "false")
    private Boolean parseSubDoc;

    @ApiModelProperty("创建人")
    private String creator;
}
