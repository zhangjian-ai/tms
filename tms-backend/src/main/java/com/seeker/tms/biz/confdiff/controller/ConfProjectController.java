package com.seeker.tms.biz.confdiff.controller;

import com.seeker.tms.biz.confdiff.entities.ConfProjectDTO;
import com.seeker.tms.biz.confdiff.entities.ConfProjectQueryDTO;
import com.seeker.tms.biz.confdiff.entities.ConfProjectVO;
import com.seeker.tms.biz.confdiff.service.ConfProjectService;
import com.seeker.tms.common.entities.PageResult;
import com.seeker.tms.common.utils.Result;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@Api(tags = "配置对比-项目管理")
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/confdiff/project")
public class ConfProjectController {

    private final ConfProjectService confProjectService;

    @ApiOperation("项目分页列表")
    @GetMapping("/list")
    public Result<PageResult<ConfProjectVO>> list(ConfProjectQueryDTO query) {
        return Result.success(confProjectService.page(query));
    }

    @ApiOperation("根据机器查询项目列表")
    @GetMapping("/listByMachine")
    public Result<List<ConfProjectVO>> listByMachine(@RequestParam Integer machineId) {
        return Result.success(confProjectService.listByMachine(machineId));
    }

    @ApiOperation("项目详情")
    @GetMapping("/detail")
    public Result<ConfProjectVO> detail(@RequestParam Integer id) {
        return Result.success(confProjectService.detail(id));
    }

    @ApiOperation("新增或编辑项目")
    @PostMapping("/saveOrUpdate")
    public Result<Integer> saveOrUpdate(@Valid @RequestBody ConfProjectDTO dto) {
        return Result.success(confProjectService.saveOrUpdateProject(dto));
    }

    @ApiOperation("删除项目")
    @PostMapping("/delete")
    public Result<?> delete(@RequestParam Integer id) {
        return confProjectService.removeProject(id) ? Result.success() : Result.fail();
    }
}
