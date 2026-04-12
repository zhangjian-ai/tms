package com.seeker.tms.biz.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.seeker.tms.biz.api.entities.ApiPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ApiMapper extends BaseMapper<ApiPO> {
}
