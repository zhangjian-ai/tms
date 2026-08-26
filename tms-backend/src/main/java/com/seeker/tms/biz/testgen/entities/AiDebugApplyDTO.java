package com.seeker.tms.biz.testgen.entities;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * AI 调试 - 应用请求：用户在预览提案后确认的最终变更集。
 * 删除按 id 直接删；修改按 id 原地重建（留原目录）；新增由用户为每条选定目标目录节点后挂入。
 */
@Data
public class AiDebugApplyDTO {
    /** 要删除的用例 id */
    private List<String> deletes;
    /** 要修改的用例：每项含 id 及完整用例字段（用例名称/优先级/前置条件/测试步骤） */
    private List<Map<String, Object>> updates;
    /** 要新增的用例及其目标目录 */
    private List<Addition> additions;
    /** 就地新建的目录（应用时先建目录再挂用例）；additions.targetNodeId 可引用其 tempId */
    private List<NewDir> newDirs;

    @Data
    public static class Addition {
        /** 目标目录节点 id（用户在结构树中单选）；可为已存在节点 id 或新建目录的 tempId */
        private String targetNodeId;
        /** 用例内容（用例名称/优先级/前置条件/测试步骤） */
        private Map<String, Object> caseData;
    }

    @Data
    public static class NewDir {
        /** 前端临时 id（如 newdir_1），供 additions.targetNodeId / 其它 newDir.parentId 引用 */
        private String tempId;
        /** 父目录：已存在节点 id、或另一条 newDir 的 tempId；为空则挂到 root 下 */
        private String parentId;
        /** 目录名称 */
        private String name;
    }
}
