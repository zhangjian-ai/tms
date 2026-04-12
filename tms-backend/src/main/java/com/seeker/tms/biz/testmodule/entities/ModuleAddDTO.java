package com.seeker.tms.biz.testmodule.entities;

import com.seeker.tms.common.enums.BoolStatus;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotNull;


@Data
@AllArgsConstructor
@NoArgsConstructor
public class ModuleAddDTO {

    @NotNull
    @ApiModelProperty("模块名称")
    private String name;

    @ApiModelProperty("父模块ID")
    private Integer parentId;

    @ApiModelProperty("是否是产品。0 不是，1 是")
    private BoolStatus isProduct = BoolStatus.FALSE;
}
