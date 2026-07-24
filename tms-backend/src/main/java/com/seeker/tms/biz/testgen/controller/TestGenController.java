package com.seeker.tms.biz.testgen.controller;

import com.seeker.tms.biz.testgen.entities.*;
import com.seeker.tms.biz.testgen.service.TestGenService;
import com.seeker.tms.common.entities.PageResult;
import com.seeker.tms.common.utils.MinioUtil;
import com.seeker.tms.common.utils.Result;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@AllArgsConstructor
@RestController
@RequestMapping("/testgen")
@Api(tags = "测试用例生成")
public class TestGenController {

    private final TestGenService testGenService;
    private final MinioUtil minioUtil;

    @ApiOperation("创建任务")
    @PostMapping("/task/create")
    public Result<Map<String, Integer>> createTask(@Validated @RequestBody TaskCreateDTO dto) {
        Integer taskId = testGenService.createTask(dto);
        return Result.success(Map.of("taskId", taskId));
    }

    @ApiOperation("任务分页列表")
    @GetMapping("/task/list")
    public Result<PageResult<TestGenTaskPO>> listTasks(TaskQueryDTO query) {
        return Result.success(testGenService.pageTasks(query));
    }

    @ApiOperation("任务信息")
    @GetMapping("/task/{taskId}")
    public Result<TaskVO> getTask(@PathVariable Integer taskId) {
        TaskVO vo = testGenService.getTask(taskId);
        return vo != null ? Result.success(vo) : Result.fail();
    }

    @ApiOperation("获取 XMind 数据")
    @GetMapping("/task/{taskId}/xmind")
    public Result<XMindNode> getXMindData(@PathVariable Integer taskId) {
        return Result.success(testGenService.getXMindData(taskId));
    }

    @ApiOperation("保存 XMind 数据")
    @RequestMapping(value = "/task/{taskId}/xmind", method = {RequestMethod.PUT, RequestMethod.POST})
    public Result<?> saveXMindData(@PathVariable Integer taskId, @RequestBody XMindNode root) {
        testGenService.saveXMindData(taskId, root);
        return Result.success();
    }

    @ApiOperation("生成章节大纲")
    @PostMapping("/task/{taskId}/plan")
    public Result<?> generatePlan(@PathVariable Integer taskId) {
        testGenService.generateOutline(taskId);
        return Result.success();
    }

    @ApiOperation("确认大纲并触发用例生成")
    @PostMapping("/task/{taskId}/confirm-plan")
    public Result<?> confirmPlan(@PathVariable Integer taskId,
                                 @RequestBody(required = false) OutlineVO outline) {
        testGenService.confirmPlan(taskId, outline);
        return Result.success();
    }

    @ApiOperation("获取当前大纲")
    @GetMapping("/task/{taskId}/outline")
    public Result<OutlineVO> getOutline(@PathVariable Integer taskId) {
        return Result.success(testGenService.getOutline(taskId));
    }

    @ApiOperation("目录节点生成用例（可带补充需求，仅追加）")
    @PostMapping("/task/{taskId}/node/{nodeId}/generate-cases")
    public Result<?> generateCasesForNode(@PathVariable Integer taskId,
                                          @PathVariable String nodeId,
                                          @RequestBody(required = false) Map<String, String> body) {
        String extraRequirement = body == null ? null : body.get("extraRequirement");
        testGenService.generateCasesForNode(taskId, nodeId, extraRequirement);
        return Result.success();
    }

    @ApiOperation("完成任务")
    @PostMapping("/task/{taskId}/finish")
    public Result<String> finishTask(@PathVariable Integer taskId) {
        String fileName = testGenService.finishTask(taskId);
        return Result.success(fileName);
    }

    @ApiOperation("重新生成")
    @PostMapping("/task/{taskId}/regenerate")
    public Result<?> regenerateTask(@PathVariable Integer taskId) {
        testGenService.regenerateTask(taskId);
        return Result.success();
    }

    @ApiOperation("恢复状态")
    @GetMapping("/task/{taskId}/restore")
    public Result<RestoreVO> restoreTask(@PathVariable Integer taskId) {
        return Result.success(testGenService.restoreTask(taskId));
    }

    @ApiOperation("删除任务")
    @DeleteMapping("/task/{taskId}")
    public Result<Void> deleteTask(@PathVariable Integer taskId) {
        testGenService.deleteTask(taskId);
        return Result.success();
    }

    @ApiOperation("获取导出文件下载链接（type=xmind|excel）")
    @GetMapping("/task/{taskId}/download-url")
    public Result<String> getDownloadUrl(@PathVariable Integer taskId,
                                         @RequestParam(required = false, defaultValue = "xmind") String type) {
        TaskVO task = testGenService.getTask(taskId);
        if (task == null) {
            return Result.fail();
        }
        String fileName = "excel".equalsIgnoreCase(type) ? task.getExcelFileName() : task.getXmindFileName();
        if (fileName == null) {
            return Result.fail();
        }
        String url = minioUtil.getUrl(fileName);
        return Result.success(url);
    }
}
