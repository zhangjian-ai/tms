package com.seeker.tms.biz.perftest.controller;

import com.seeker.tms.biz.perftest.entities.PerfTestDTO;
import com.seeker.tms.biz.perftest.service.PerfTestService;
import com.seeker.tms.common.utils.Result;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/perf")
@Api(tags = "性能测试")
@Slf4j
@AllArgsConstructor
public class PerfTestController {

    private final PerfTestService perfTestService;

    @ApiOperation("新增测试")
    @PostMapping("/addTest")
    public Result<?> addTest(@RequestBody PerfTestDTO perfTestDTO){
        perfTestService.addTest(perfTestDTO);
        return Result.success();
    }
}
