package com.seeker.tms.biz.testgen.entities;

import com.seeker.tms.common.entities.PageReq;
import io.swagger.annotations.ApiModel;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
@ApiModel("用例生成任务分页查询")
public class TaskQueryDTO extends PageReq {
}
