package com.seeker.tms.biz.testcase.entities;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("test_case")
public class TestCasePO {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String name;
    private Integer moduleId;
    private Integer autoId;
    private Integer priority;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
