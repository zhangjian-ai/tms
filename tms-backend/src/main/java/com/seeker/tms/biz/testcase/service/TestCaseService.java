package com.seeker.tms.biz.testcase.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.seeker.tms.biz.testcase.entities.CaseAddDTO;
import com.seeker.tms.biz.testcase.entities.TestCasePO;


public interface TestCaseService extends IService<TestCasePO> {

    Boolean addTestCase(CaseAddDTO caseAddDTO);
}
