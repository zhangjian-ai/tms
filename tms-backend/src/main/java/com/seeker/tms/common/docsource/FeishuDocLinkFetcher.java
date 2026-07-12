package com.seeker.tms.common.docsource;

import com.seeker.tms.common.feishu.FeishuDocClient;
import com.seeker.tms.common.feishu.UserFeishuTokenProvider;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 飞书文档链接抓取器：支持新版文档 docx、知识库 wiki、电子表格 sheets。
 * 以任务创建者的飞书授权身份访问受控文档，可选解析内嵌图片、发现二级文档引用。
 */
@Slf4j
@Component
@AllArgsConstructor
public class FeishuDocLinkFetcher implements DocLinkFetcher {

    /** 匹配 /docx/{token}、/docs/{token}、/wiki/{token}、/sheets/{token}、/base/{token} */
    private static final Pattern LINK_PATTERN =
            Pattern.compile("/(docx|docs|wiki|sheets|base)/([A-Za-z0-9]+)");

    /** 二级文档发现的数量上限 */
    private static final int MAX_RELATED = 10;

    private final UserFeishuTokenProvider tokenProvider;
    private final FeishuDocClient docClient;
    /** OCR 能力可缺省（未配置视觉模型时内嵌图片解析降级为剔除占位符） */
    private final ObjectProvider<ImageTextRecognizer> recognizerProvider;

    @Override
    public boolean supports(String url) {
        if (url == null) return false;
        boolean feishuHost = url.contains("feishu.cn") || url.contains("larksuite.com");
        return feishuHost && LINK_PATTERN.matcher(url).find();
    }

    @Override
    public FetchedDoc fetch(String url, String username, DocFetchOptions options) {
        Matcher m = LINK_PATTERN.matcher(url);
        if (!m.find()) {
            throw new IllegalArgumentException("无法识别的飞书文档链接: " + url);
        }
        String type = normalizeType(m.group(1));
        String token = m.group(2);
        DocFetchOptions opts = options != null ? options : DocFetchOptions.none();

        String userToken = tokenProvider.getUserAccessToken(username);
        return fetchByType(type, token, userToken, null, opts);
    }

    /** 按文档类型抓取；wiki 先解析出真实挂载文档再递归 */
    private FetchedDoc fetchByType(String type, String token, String userToken,
                                   String titleHint, DocFetchOptions options) {
        switch (type) {
            case "docx": {
                FeishuDocClient.DocxContent doc = docClient.getDocxContent(token, userToken, options.isParseImage());
                String text = doc.getText();
                if (options.isParseImage()) {
                    text = fillImages(text, doc.getImages(), userToken);
                }
                String title = titleHint != null ? titleHint : doc.getTitle();
                List<String> related = options.isParseSubDoc()
                        ? filterRelated(doc.getRefLinks()) : Collections.emptyList();
                return new FetchedDoc(new DocContent(title, text), related);
            }
            case "sheet": {
                String text = docClient.getSheetContent(token, userToken);
                return new FetchedDoc(new DocContent(titleHint, text), Collections.emptyList());
            }
            case "wiki": {
                FeishuDocClient.WikiNode node = docClient.getWikiNode(token, userToken);
                String objType = normalizeType(node.getObjType());
                String hint = titleHint != null ? titleHint : node.getTitle();
                return fetchByType(objType, node.getObjToken(), userToken, hint, options);
            }
            case "doc":
                throw new IllegalArgumentException("暂不支持旧版文档(doc)，请使用新版文档(docx)链接");
            default:
                throw new IllegalArgumentException("暂不支持的飞书文档类型: " + type);
        }
    }

    /** 下载并识别内嵌图片，回填到 [[IMG:n]] 占位符；无 OCR 能力或失败则剔除占位符 */
    private String fillImages(String text, List<FeishuDocClient.ImageRef> images, String userToken) {
        if (text == null) return "";
        if (images == null || images.isEmpty()) return text;
        ImageTextRecognizer recognizer = recognizerProvider != null ? recognizerProvider.getIfAvailable() : null;
        for (int i = 0; i < images.size(); i++) {
            String placeholder = "[[IMG:" + i + "]]";
            String replacement = "";
            try {
                if (recognizer != null) {
                    byte[] bytes = docClient.downloadMedia(images.get(i).getFileToken(), userToken);
                    if (bytes != null && bytes.length > 0) {
                        String recognized = recognizer.recognize(bytes, null);
                        if (recognized != null && !recognized.isBlank()) {
                            replacement = "\n[图片内容]\n" + recognized.trim() + "\n";
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("飞书文档内嵌图片识别失败: {}", e.toString());
            }
            text = text.replace(placeholder, replacement);
        }
        return text;
    }

    /** 过滤为受支持的飞书文档链接，去重并限量 */
    private List<String> filterRelated(List<String> refLinks) {
        if (refLinks == null || refLinks.isEmpty()) return Collections.emptyList();
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String link : refLinks) {
            if (supports(link)) {
                set.add(link);
                if (set.size() >= MAX_RELATED) break;
            }
        }
        return new ArrayList<>(set);
    }

    /** 统一 URL 段/飞书 obj_type 到内部类型 */
    private String normalizeType(String raw) {
        if (raw == null) return "";
        switch (raw) {
            case "docs":
                return "doc";
            case "sheets":
            case "sheet":
                return "sheet";
            default:
                return raw; // docx / wiki / doc / base ...
        }
    }
}
