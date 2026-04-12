package com.seeker.tms.common.entities;

import lombok.Data;

import java.util.List;

@Data
public class PageResult<T> {
    // 数据总数
    private Integer total;
    // 当前页码
    private Integer pageNo;
    // 页码总数
    private Integer pageCount;
    // 数据详情列表
    private List<T> list;
}
