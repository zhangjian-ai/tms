package com.seeker.tms.biz.perftest.entities;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.seeker.tms.biz.api.entities.ApiInvokeDTO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName(value = "perf_test", autoResultMap = true)  // autoResultMap 查询时自动把数据映射为Po中的字段类型
public class PerfTestPO {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String name;
    private String dataset;
    private String proto;
    private String compress;
    private String encryption;

    @TableField(typeHandler = JacksonTypeHandler.class)  // 实现保存和查询时的数据类型转换
    private List<ApiInvokeDTO> apiInvokes;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
