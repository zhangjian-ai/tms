package com.seeker.tms.biz.testmodule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.seeker.tms.biz.testmodule.entities.ModulePO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ModuleMapper extends BaseMapper<ModulePO> {}
