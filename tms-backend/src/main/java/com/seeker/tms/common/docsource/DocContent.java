package com.seeker.tms.common.docsource;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 从链接抓取到的文档内容。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocContent {
    /** 文档标题（可能为空） */
    private String title;
    /** 文档正文纯文本 */
    private String text;
}
