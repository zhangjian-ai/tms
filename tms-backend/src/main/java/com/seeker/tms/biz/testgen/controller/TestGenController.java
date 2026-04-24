package com.seeker.tms.biz.testgen.controller;

import com.seeker.tms.biz.testgen.entities.*;
import com.seeker.tms.biz.testgen.service.AgentChatService;
import com.seeker.tms.biz.testgen.service.TestGenService;
import com.seeker.tms.common.utils.MinioUtil;
import com.seeker.tms.common.utils.Result;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@AllArgsConstructor
@RestController
@RequestMapping("/testgen")
@Api(tags = "测试用例生成")
public class TestGenController {

    private final TestGenService testGenService;
    private final AgentChatService agentChatService;
    private final MinioUtil minioUtil;

    @ApiOperation("创建任务")
    @PostMapping("/task/create")
    public Result<Map<String, Integer>> createTask(@Validated @RequestBody TaskCreateDTO dto) {
        Integer taskId = testGenService.createTask(dto);
        return Result.success(Map.of("taskId", taskId));
    }

    @ApiOperation("任务列表")
    @GetMapping("/task/list")
    public Result<List<TestGenTaskPO>> listTasks() {
        return Result.success(testGenService.listTasks());
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

    @ApiOperation("生成测试点")
    @PostMapping("/task/{taskId}/points")
    public Result<?> generatePoints(@PathVariable Integer taskId) {
        testGenService.generatePoints(taskId);
        return Result.success();
    }

    @ApiOperation("单个测试点生成用例")
    @PostMapping("/task/{taskId}/point/{pointId}/generate")
    public Result<?> generateCasesForPoint(@PathVariable Integer taskId,
                                            @PathVariable String pointId) {
        testGenService.generateCasesForPoint(taskId, pointId);
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

    @ApiOperation("获取 XMind 下载链接")
    @GetMapping("/task/{taskId}/download-url")
    public Result<String> getDownloadUrl(@PathVariable Integer taskId) {
        TaskVO task = testGenService.getTask(taskId);
        if (task == null || task.getXmindFileName() == null) {
            return Result.fail();
        }
        String url = minioUtil.getUrl(task.getXmindFileName());
        return Result.success(url);
    }
}
