package com.seeker.tms.common.docsource;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档链接抓取门面：按链接类型分派到具体 {@link DocLinkFetcher}。
 * 通用能力，供任意业务（如用例生成）复用。
 */
@Slf4j
@Service
@AllArgsConstructor
public class DocumentLinkService {

    private final List<DocLinkFetcher> fetchers;

    /** 是否有可处理该链接的抓取器 */
    public boolean isSupported(String url) {
        return fetchers.stream().anyMatch(f -> f.supports(url));
    }

    /**
     * 以指定用户身份抓取「主文档 + 关联文档」：
     * 先抓主文档；当 {@link DocFetchOptions#isParseSubDoc()} 开启时，对主文档中发现的
     * 引用链接再抓一层（不递归），作为关联文档追加。返回列表主文档在前、关联文档在后。
     *
     * @throws IllegalArgumentException 无匹配的抓取器
     */
    public List<DocContent> fetchAll(String url, String username, DocFetchOptions options) {
        DocFetchOptions opts = options != null ? options : DocFetchOptions.none();
        DocLinkFetcher fetcher = pick(url);
        FetchedDoc main = fetcher.fetch(url, username, opts);

        List<DocContent> result = new ArrayList<>();
        result.add(main.getContent());

        if (opts.isParseSubDoc() && main.getRelatedLinks() != null) {
            // 二级文档仅取 1 层：抓取关联文档时关闭 parseSubDoc，避免递归
            DocFetchOptions subOpts = new DocFetchOptions(opts.isParseImage(), false);
            for (String link : main.getRelatedLinks()) {
                try {
                    DocLinkFetcher sub = pick(link);
                    result.add(sub.fetch(link, username, subOpts).getContent());
                } catch (Exception e) {
                    log.warn("抓取关联文档失败，跳过: {}: {}", link, e.toString());
                }
            }
        }
        return result;
    }

    private DocLinkFetcher pick(String url) {
        for (DocLinkFetcher fetcher : fetchers) {
            if (fetcher.supports(url)) {
                return fetcher;
            }
        }
        throw new IllegalArgumentException("暂不支持的文档链接: " + url);
    }
}
