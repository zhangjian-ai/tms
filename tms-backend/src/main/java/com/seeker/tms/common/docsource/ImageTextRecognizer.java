package com.seeker.tms.common.docsource;

/**
 * 图片文本识别能力（OCR/视觉理解）。定义在 common 层，由 biz 层实现，
 * 供 common 的文档抓取器对文档内嵌图片做识别，避免 common→biz 反向依赖。
 */
public interface ImageTextRecognizer {

    /**
     * 识别图片内容为文本。
     * @param image           图片字节
     * @param surroundingText 图片周边上下文（可为空），辅助识别
     * @return 识别出的文本；失败可返回 null
     */
    String recognize(byte[] image, String surroundingText);
}
