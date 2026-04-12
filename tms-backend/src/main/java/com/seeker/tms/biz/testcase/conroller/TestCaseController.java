package com.seeker.tms.biz.testcase.conroller;

import com.seeker.tms.biz.testcase.entities.CaseAddDTO;
import com.seeker.tms.biz.testcase.service.TestCaseService;
import com.seeker.tms.common.utils.Result;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@AllArgsConstructor
@RestController
@RequestMapping("/testcase")
@Api(tags = "用例管理")
public class TestCaseController {

    private final TestCaseService testCaseService;

    @ApiOperation("新增用例")
    @PostMapping("/add")
    public Result<?> addTestCase(CaseAddDTO caseAddDTO){
        boolean status = testCaseService.addTestCase(caseAddDTO);
        return status ? Result.success() : Result.fail();
    }
}
