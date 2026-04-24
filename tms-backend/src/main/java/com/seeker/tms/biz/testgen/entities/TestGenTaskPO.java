package com.seeker.tms.biz.testgen.entities;

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
@TableName("test_gen_task")
public class TestGenTaskPO {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String prdName;
    private String prdType;
    private String status;
    private String message;
    private String xmindFileName;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
