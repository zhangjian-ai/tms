package com.seeker.tms.biz.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.seeker.tms.biz.common.entities.UserPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<UserPO> {
}
