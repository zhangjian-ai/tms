package com.seeker.tms.biz.testgen.controller;

import com.seeker.tms.biz.testgen.entities.*;
import com.seeker.tms.biz.testgen.service.TestGenService;
import com.seeker.tms.biz.testgen.service.impl.CaseExcelBuilder;
import com.seeker.tms.biz.testgen.service.impl.XMindBuilder;
import com.seeker.tms.biz.testgen.utils.XMindTrees;
import com.seeker.tms.biz.testgen.websocket.TestGenWebSocketHandler;
import com.seeker.tms.common.auth.UserContext;
import com.seeker.tms.common.entities.PageResult;
import com.seeker.tms.common.enums.ResultStatus;
import com.seeker.tms.common.utils.MinioUtil;
import com.seeker.tms.common.utils.Result;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
        // 任务被他人占用（编辑锁）时拒绝保存
        String username = UserContext.get();
        if (!TestGenWebSocketHandler.canEdit(String.valueOf(taskId), username)) {
            log.warn("拒绝保存 XMind：任务 {} 正被他人占用，请求者={}", taskId, username);
            return Result.builder(ResultStatus.FAILED.getCode(),
                    "任务正被他人编辑，您当前为只读模式，保存被拒绝", null);
        }
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

    @ApiOperation("临时导出选定用例子树（直接下载，不落 MinIO、不改任务状态）")
    @PostMapping("/task/{taskId}/export")
    public ResponseEntity<byte[]> exportTemp(@PathVariable Integer taskId,
                                             @RequestParam(required = false, defaultValue = "xmind") String type,
                                             @RequestBody XMindNode root) {
        if (root == null) {
            return ResponseEntity.badRequest().build();
        }
        byte[] bytes;
        String ext;
        String contentType;
        if ("excel".equalsIgnoreCase(type)) {
            // Excel：collectRows 内部已跳过 free 节点，直接用原始 root（与 finishTask 一致）
            bytes = CaseExcelBuilder.build(root);
            ext = ".xlsx";
            contentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        } else {
            // XMind：先剔除 free 自由节点，再渲染（与 finishTask 一致）
            bytes = XMindBuilder.build(XMindTrees.filterForExport(root));
            ext = ".xmind";
            contentType = "application/octet-stream";
        }
        String base = (root.getTitle() != null && !root.getTitle().isBlank())
                ? root.getTitle() : ("export_" + taskId);
        String encoded = URLEncoder.encode(base + ext, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.parseMediaType(contentType))
                .body(bytes);
    }

    // ---- AI 调试（用户驱动的二次优化：删 / 改 / 增） ----

    @ApiOperation("AI 调试 - 提案（调 LLM 产出删/改/增，不改树）")
    @PostMapping("/task/{taskId}/ai-debug")
    public Result<AiDebugResultVO> aiDebugPropose(@PathVariable Integer taskId,
                                                  @RequestBody AiDebugRequestDTO req) {
        String username = UserContext.get();
        if (!TestGenWebSocketHandler.canEdit(String.valueOf(taskId), username)) {
            return Result.builder(ResultStatus.FAILED.getCode(),
                    "任务正被他人编辑，您当前为只读模式，无法进行 AI 调试", null);
        }
        return Result.success(testGenService.aiDebugPropose(taskId, req));
    }

    @ApiOperation("AI 调试 - 应用（把确认的删/改/增写入用例树并刷新）")
    @PostMapping("/task/{taskId}/ai-debug/apply")
    public Result<XMindNode> aiDebugApply(@PathVariable Integer taskId,
                                          @RequestBody AiDebugApplyDTO dto) {
        String username = UserContext.get();
        if (!TestGenWebSocketHandler.canEdit(String.valueOf(taskId), username)) {
            return Result.builder(ResultStatus.FAILED.getCode(),
                    "任务正被他人编辑，您当前为只读模式，应用被拒绝", null);
        }
        return Result.success(testGenService.aiDebugApply(taskId, dto));
    }

    @ApiOperation("AI 调试 - 历史记录（当前用户+任务，最多 30 条）")
    @GetMapping("/task/{taskId}/ai-debug/history")
    public Result<java.util.List<AiDebugHistoryVO>> aiDebugHistory(@PathVariable Integer taskId) {
        return Result.success(testGenService.getAiDebugHistory(taskId));
    }
}
