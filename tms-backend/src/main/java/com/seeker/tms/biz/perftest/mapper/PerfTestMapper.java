package com.seeker.tms.biz.perftest.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.seeker.tms.biz.perftest.entities.PerfTestPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PerfTestMapper extends BaseMapper<PerfTestPO> {
}
