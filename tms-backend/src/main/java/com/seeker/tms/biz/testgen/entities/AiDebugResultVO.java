package com.seeker.tms.biz.testgen.entities;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * AI 调试 - 提案结果：模型给出的「删除 / 修改 / 新增」三类变更，仅返回给前端预览，尚未写入用例树。
 * 用例内容用 Map 承载（键为中文：用例名称/优先级/前置条件/测试步骤），便于前端展示并原样回传给 apply。
 */
@Data
public class AiDebugResultVO {
    /** 待删除用例：每项 {id, 用例名称} */
    private List<Map<String, Object>> deletes;
    /** 待修改用例：每项 {id, 用例名称, 优先级, 前置条件, 测试步骤} —— 按 id 原地整体重建 */
    private List<Map<String, Object>> updates;
    /** 待新增用例：每项 {tempId, 用例名称, 优先级, 前置条件, 测试步骤} —— tempId 供前端映射目录选择 */
    private List<Map<String, Object>> additions;
}
