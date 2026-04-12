package com.seeker.tms.biz.testcase.entities;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.seeker.tms.common.enums.BoolStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("test_step")
public class TestStepPO {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String detail;
    private String result;
    private BoolStatus isCondition = BoolStatus.FALSE;
    private Integer caseId;
    private Integer order;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
