package com.seeker.tms.biz.testmodule.controller;

import com.seeker.tms.biz.testmodule.entities.ModuleAddDTO;
import com.seeker.tms.biz.testmodule.entities.ModulePO;
import com.seeker.tms.biz.testmodule.entities.ModuleUpdateDTO;
import com.seeker.tms.biz.testmodule.service.ModuleService;
import com.seeker.tms.common.utils.Result;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@Api(tags = "功能测试")
@Slf4j
@AllArgsConstructor
@RestController
@RequestMapping("/module")
public class ModuleController {

    private final ModuleService moduleService;

    @ApiOperation("新增模块")
    @PostMapping("/add")
    public Result<?> addModule(@Valid @RequestBody ModuleAddDTO moduleAddDTO) {
        Boolean result = moduleService.addModule(moduleAddDTO);
        return result ? Result.success() : Result.fail();
    }

    @ApiOperation("删除模块")
    @PostMapping("/delete")
    public Result<?> deleteModule(@Valid @RequestBody ModuleUpdateDTO moduleUpdateDTO) {
        Boolean result = moduleService.deleteModule(moduleUpdateDTO);
        return result ? Result.success() : Result.fail();
    }

    @ApiOperation("更新模块")
    @PostMapping("/update")
    public Result<?> updateModule(@Valid @RequestBody ModuleUpdateDTO moduleUpdateDTO) {
        Boolean result = moduleService.updateModule(moduleUpdateDTO);
        return result ? Result.success() : Result.fail();
    }

    @ApiOperation("查询所有模块")
    @GetMapping("/list")
    public Result<List<ModulePO>> listModule() {
        List<ModulePO> modulePOS = moduleService.listModule();
        return Result.success(modulePOS);
    }


}
