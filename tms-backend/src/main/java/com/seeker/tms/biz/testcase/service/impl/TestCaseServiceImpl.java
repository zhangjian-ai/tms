package com.seeker.tms.biz.testcase.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.seeker.tms.biz.testcase.entities.CaseAddDTO;
import com.seeker.tms.biz.testcase.entities.TestCasePO;
import com.seeker.tms.biz.testcase.entities.StepAddDTO;
import com.seeker.tms.biz.testcase.entities.TestStepPO;
import com.seeker.tms.biz.testcase.mapper.TestCaseMapper;
import com.seeker.tms.biz.testcase.service.TestCaseService;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class TestCaseServiceImpl extends ServiceImpl<TestCaseMapper, TestCasePO> implements TestCaseService {
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean addTestCase(CaseAddDTO caseAddDTO) {
        // 保存用例主体
        TestCasePO testCasePo = new TestCasePO();
        testCasePo.setName(caseAddDTO.getName());
        testCasePo.setModuleId(caseAddDTO.getModuleId());
        testCasePo.setPriority(caseAddDTO.getPriority());
        testCasePo.setCreateTime(LocalDateTime.now());
        testCasePo.setUpdateTime(LocalDateTime.now());

        // 新增成功后 testCasePo 的id会被赋值
        boolean result = this.save(testCasePo);

        if (!result) {
            log.error("测试用例保存失败，用例名称: " + testCasePo.getName());
            return false;
        }

        List<StepAddDTO> stepAddDTOS = caseAddDTO.getSteps();
        ArrayList<TestStepPO> testStepPOS = new ArrayList<>(stepAddDTOS.size());

        int order = 1;
        for (StepAddDTO StepAddDTO : stepAddDTOS) {
            TestStepPO testStepPo = BeanUtil.copyProperties(StepAddDTO, TestStepPO.class);

            testStepPo.setCaseId(testCasePo.getId());
            testStepPo.setOrder(order);

            testStepPo.setCreateTime(LocalDateTime.now());
            testStepPo.setUpdateTime(LocalDateTime.now());

            testStepPOS.add(testStepPo);
            order += 1;
        }
        // 批量保存测试步骤
        boolean status = Db.saveBatch(testStepPOS);
        if (!status){
            log.error("测试用例的测试步骤保存失败，用例ID: " + testCasePo.getId().toString());
        }
        return status;
    }
}
