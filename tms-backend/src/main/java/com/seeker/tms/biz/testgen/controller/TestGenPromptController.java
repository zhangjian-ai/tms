package com.seeker.tms.biz.testgen.controller;

import com.seeker.tms.biz.testgen.model.PromptDTO;
import com.seeker.tms.biz.testgen.model.PromptQueryDTO;
import com.seeker.tms.biz.testgen.model.PromptStageVO;
import com.seeker.tms.biz.testgen.model.PromptVO;
import com.seeker.tms.biz.testgen.service.TestGenPromptService;
import com.seeker.tms.common.entities.PageResult;
import com.seeker.tms.common.utils.Result;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@Api(tags = "系统管理-提示词管理")
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/testgen/prompt")
public class TestGenPromptController {

    private final TestGenPromptService promptService;

    @ApiOperation("提示词分页列表")
    @GetMapping("/list")
    public Result<PageResult<PromptVO>> list(PromptQueryDTO query) {
        return Result.success(promptService.page(query));
    }

    @ApiOperation("提示词详情(含内容)")
    @GetMapping("/detail")
    public Result<PromptVO> detail(@RequestParam Integer id) {
        return Result.success(promptService.detail(id));
    }

    @ApiOperation("阶段字典(含各阶段当前绑定的提示词)")
    @GetMapping("/stages")
    public Result<List<PromptStageVO>> stages() {
        return Result.success(promptService.listStages());
    }

    @ApiOperation("新增或编辑提示词,绑定的阶段被占用时自动接管")
    @PostMapping("/saveOrUpdate")
    public Result<Integer> saveOrUpdate(@Valid @RequestBody PromptDTO dto) {
        return Result.success(promptService.saveOrUpdate(dto));
    }

    @ApiOperation("删除提示词")
    @PostMapping("/delete")
    public Result<?> delete(@RequestParam Integer id) {
        return promptService.remove(id) ? Result.success() : Result.fail();
    }
}
