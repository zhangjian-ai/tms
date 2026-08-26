package com.seeker.tms.biz.testgen.entities;

import lombok.Data;

import java.util.List;

/**
 * AI 调试 - 提案请求：用户填写的系统/用户提示词，以及要参考的大纲条目与用例。
 * 系统提示词为用户可见部分，后端会在其前拼接隐藏的兜底提示词；
 * 用户提示词中可用 {{需求文档}}、{{大纲}}、{{用例}} 占位符引用后端材料。
 */
@Data
public class AiDebugRequestDTO {
    /** 用户填写的系统提示词（可见部分） */
    private String systemPrompt;
    /** 用户填写的用户提示词，可含 {{需求文档}}/{{大纲}}/{{用例}} 占位符 */
    private String userPrompt;
    /** 勾选的大纲章节 id（用于拼装 {{大纲}}），为空则不引用大纲 */
    private List<String> chapterIds;
    /** 勾选的用例 id（用于拼装 {{用例}}），为空则不引用用例 */
    private List<String> caseIds;
}
