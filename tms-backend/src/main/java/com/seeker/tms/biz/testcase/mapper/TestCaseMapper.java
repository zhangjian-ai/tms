package com.seeker.tms.biz.testcase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.seeker.tms.biz.testcase.entities.TestCasePO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TestCaseMapper extends BaseMapper<TestCasePO> {
}
