package com.seeker.tms.common.docsource;

/**
 * 文档链接抓取器：按链接类型实现（飞书等）。通用能力，不绑定具体业务。
 */
public interface DocLinkFetcher {

    /** 是否支持该链接 */
    boolean supports(String url);

    /**
     * 抓取文档内容。
     * @param url      文档链接
     * @param username 以该用户的授权身份访问（受控文档需要用户 token）
     * @param options  抓取选项（是否解析内嵌图片、是否发现二级文档引用）
     * @return 文档内容 + 发现的二级文档引用链接
     */
    FetchedDoc fetch(String url, String username, DocFetchOptions options);
}
