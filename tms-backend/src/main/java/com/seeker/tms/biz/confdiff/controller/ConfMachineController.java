package com.seeker.tms.biz.confdiff.controller;

import com.seeker.tms.biz.confdiff.entities.ConfMachineDTO;
import com.seeker.tms.biz.confdiff.entities.ConfMachineQueryDTO;
import com.seeker.tms.biz.confdiff.entities.ConfMachineVO;
import com.seeker.tms.biz.confdiff.service.ConfMachineService;
import com.seeker.tms.common.entities.PageResult;
import com.seeker.tms.common.utils.Result;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

@Api(tags = "配置对比-机器管理")
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/confdiff/machine")
public class ConfMachineController {

    private final ConfMachineService confMachineService;

    @ApiOperation("机器分页列表")
    @GetMapping("/list")
    public Result<PageResult<ConfMachineVO>> list(ConfMachineQueryDTO query) {
        return Result.success(confMachineService.page(query));
    }

    @ApiOperation("机器详情")
    @GetMapping("/detail")
    public Result<ConfMachineVO> detail(@RequestParam Integer id) {
        return Result.success(confMachineService.detail(id));
    }

    @ApiOperation("新增或编辑机器")
    @PostMapping("/saveOrUpdate")
    public Result<Integer> saveOrUpdate(@Valid @RequestBody ConfMachineDTO dto) {
        return Result.success(confMachineService.saveOrUpdateMachine(dto));
    }

    @ApiOperation("删除机器(级联删除其项目)")
    @PostMapping("/delete")
    public Result<?> delete(@RequestParam Integer id) {
        return confMachineService.removeMachine(id) ? Result.success() : Result.fail();
    }

    @ApiOperation("测试机器SSH连通性")
    @PostMapping("/testConnection")
    public Result<?> testConnection(@RequestParam Integer id) {
        return confMachineService.testConnection(id) ? Result.success() : Result.fail();
    }
}
