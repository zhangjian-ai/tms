package com.seeker.tms.biz.api.entities;

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
@TableName("api")
public class ApiPO {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String name;
    private String proto;
    private String url;
    private String method;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
