package com.seeker.tms.common.docsource;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单个文档的抓取结果：文档内容 + 其中发现的、可作为二级文档的引用链接。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FetchedDoc {

    /** 主文档内容 */
    private DocContent content;

    /** 文档中直接引用的、受支持的其它文档链接（供上层按需抓取为关联文档） */
    private List<String> relatedLinks;
}
