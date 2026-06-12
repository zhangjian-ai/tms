package com.seeker.tms.biz.confdiff.controller;

import com.seeker.tms.biz.confdiff.entities.CommitInfo;
import com.seeker.tms.biz.confdiff.entities.CompareRequestDTO;
import com.seeker.tms.biz.confdiff.entities.PrepareStatusVO;
import com.seeker.tms.biz.confdiff.entities.compare.CompareHistoryVO;
import com.seeker.tms.biz.confdiff.entities.compare.CompareResultVO;
import com.seeker.tms.biz.confdiff.service.ConfCompareService;
import com.seeker.tms.common.utils.Result;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@Api(tags = "配置对比-执行对比")
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/confdiff/compare")
public class ConfCompareController {

    private final ConfCompareService confCompareService;

    @ApiOperation("远程触发配置对比(分支或commit)")
    @PostMapping("/run")
    public Result<CompareResultVO> run(@Valid @RequestBody CompareRequestDTO request) {
        return Result.success(confCompareService.compare(request));
    }

    @ApiOperation("触发项目准备(首次异步clone)")
    @PostMapping("/prepare")
    public Result<PrepareStatusVO> prepare(@RequestParam Integer projectId) {
        return Result.success(confCompareService.prepare(projectId));
    }

    @ApiOperation("查询项目准备状态")
    @GetMapping("/prepareStatus")
    public Result<PrepareStatusVO> prepareStatus(@RequestParam Integer projectId) {
        return Result.success(confCompareService.prepareStatus(projectId));
    }

    @ApiOperation("列出项目的远程分支")
    @GetMapping("/branches")
    public Result<List<String>> branches(@RequestParam Integer projectId) {
        return Result.success(confCompareService.listBranches(projectId));
    }

    @ApiOperation("列出某分支上的提交")
    @GetMapping("/commits")
    public Result<List<CommitInfo>> commits(@RequestParam Integer projectId,
                                            @RequestParam String branch,
                                            @RequestParam(required = false) Integer limit) {
        return Result.success(confCompareService.listCommits(projectId, branch, limit));
    }

    @ApiOperation("项目对比历史列表")
    @GetMapping("/history")
    public Result<List<CompareHistoryVO>> history(@RequestParam Integer projectId) {
        return Result.success(confCompareService.history(projectId));
    }

    @ApiOperation("按ID回看历史对比结果")
    @GetMapping("/result")
    public Result<CompareResultVO> result(@RequestParam String id) {
        return Result.success(confCompareService.getResult(id));
    }
}
