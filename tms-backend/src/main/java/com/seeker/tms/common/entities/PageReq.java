package com.seeker.tms.common.entities;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

@Data
public class PageReq {
    @ApiModelProperty("页码")
    private Integer pageNo = 1;

    @ApiModelProperty("每页大小")
    private Integer pageSize = 10;

    @ApiModelProperty("排序字段")
    private String sortBy;

    @ApiModelProperty("是否升序")
    private boolean asc = false;
}
