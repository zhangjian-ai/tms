package com.seeker.tms.biz.testgen.entities;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 用例生成大纲：由 PlanningAgent 在阅读需求文档后产出，
 * 是对原始需求文档的"章节摘要"——每一项对应文档中一个相对独立的内容板块。
 * 后续测试点提取阶段会按章节维度切分上下文调 LLM。
 * 用户可以在前端编辑后确认。
 */
@Data
public class OutlineVO {

    private List<ModuleNode> modules = new ArrayList<>();

    /** 文档总体摘要 */
    private String summary;

    @Data
    public static class ModuleNode {
        /** 章节名/主题名，与需求文档中的章节标题对应 */
        private String name;
        /** 该章节的摘要描述（2-4 句话） */
        private String scope;
    }
}
