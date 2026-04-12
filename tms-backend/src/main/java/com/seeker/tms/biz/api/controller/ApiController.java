package com.seeker.tms.biz.api.controller;

import com.seeker.tms.biz.api.entities.ApiAddDTO;

import com.seeker.tms.biz.api.entities.ApiInvokeDTO;
import com.seeker.tms.biz.api.entities.ApiInvokeVO;
import com.seeker.tms.biz.api.service.ApiService;
import com.seeker.tms.common.utils.Result;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Api(tags = "接口管理")
public class ApiController {
    private final ApiService apiService;

    @ApiOperation("新增")
    @PostMapping("/add")
    public Result<?> addApi(@RequestBody @Valid ApiAddDTO apiAddDTO){
        boolean isSuccess = apiService.addApi(apiAddDTO);
        return isSuccess ? Result.success() : Result.fail();
    }

    @ApiOperation("调用")
    @PostMapping("/invoke")
    public Result<ApiInvokeVO> invokeApi(@RequestBody @Valid ApiInvokeDTO apiInvokeDTO){
        ApiInvokeVO data = apiService.invokeApi(apiInvokeDTO);
        return Result.success(data);
    }
}
