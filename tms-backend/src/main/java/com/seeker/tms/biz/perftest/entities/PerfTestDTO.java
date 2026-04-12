package com.seeker.tms.biz.perftest.entities;

import com.seeker.tms.biz.api.entities.ApiInvokeDTO;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotNull;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PerfTestDTO {
    @NotNull
    @ApiModelProperty("任务名称")
    private String name;

    @ApiModelProperty("数据集文件名称，只能是带标题的xlsx、csv文件")
    private String dataset;

    @ApiModelProperty("协议文件名称")
    private String proto;

    @ApiModelProperty("数据压缩方式")
    private String compress;

    @ApiModelProperty("数据加密方法")
    private String encryption;

    @ApiModelProperty("接口调用详情列表")
    private List<ApiInvokeDTO> apiInvokes;

}
