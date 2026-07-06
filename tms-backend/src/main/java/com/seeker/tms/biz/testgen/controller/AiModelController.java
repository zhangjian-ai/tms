package com.seeker.tms.biz.testgen.controller;

import com.seeker.tms.biz.testgen.model.AiModelDTO;
import com.seeker.tms.biz.testgen.model.AiModelQueryDTO;
import com.seeker.tms.biz.testgen.model.AiModelVO;
import com.seeker.tms.biz.testgen.service.AiModelService;
import com.seeker.tms.common.entities.PageResult;
import com.seeker.tms.common.utils.Result;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

@Api(tags = "系统管理-模型管理")
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/testgen/model")
public class AiModelController {

    private final AiModelService aiModelService;

    @ApiOperation("模型分页列表")
    @GetMapping("/list")
    public Result<PageResult<AiModelVO>> list(AiModelQueryDTO query) {
        return Result.success(aiModelService.page(query));
    }

    @ApiOperation("模型详情")
    @GetMapping("/detail")
    public Result<AiModelVO> detail(@RequestParam Integer id) {
        return Result.success(aiModelService.detail(id));
    }

    @ApiOperation("新增或编辑模型")
    @PostMapping("/saveOrUpdate")
    public Result<Integer> saveOrUpdate(@Valid @RequestBody AiModelDTO dto) {
        return Result.success(aiModelService.saveOrUpdateModel(dto));
    }

    @ApiOperation("快速标记模型角色(thinking/vision),生效时自动取消其他模型的同一角色")
    @PostMapping("/mark")
    public Result<?> mark(@RequestParam Integer id,
                          @RequestParam String role,
                          @RequestParam boolean marked) {
        aiModelService.mark(id, role, marked);
        return Result.success();
    }

    @ApiOperation("删除模型")
    @PostMapping("/delete")
    public Result<?> delete(@RequestParam Integer id) {
        return aiModelService.removeModel(id) ? Result.success() : Result.fail();
    }
}
