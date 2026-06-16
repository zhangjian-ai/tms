package com.seeker.tms.biz.testgen.service;

import java.util.function.BiConsumer;

public interface DocumentParserService {

    /**
     * 解析需求文档为纯文本。
     *
     * @param parseImage true 时解析文档内图片并将识别结果回填到原文位置；
     *                   false 时仅提取文本，剔除图片占位符。
     */
    String parseDocument(String url, String fileName, boolean parseImage, BiConsumer<Integer, String> progressCallback);

    String recognizeImage(byte[] imageBytes);

    String recognizeImage(byte[] imageBytes, String surroundingText);
}
