package com.seeker.tms.common.docsource;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档抓取选项。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocFetchOptions {

    /** 是否解析文档内嵌图片（下载并 OCR 回填到正文） */
    private boolean parseImage;

    /** 是否抓取主文档直接引用的二级文档（仅 1 层） */
    private boolean parseSubDoc;

    public static DocFetchOptions none() {
        return new DocFetchOptions(false, false);
    }
}
