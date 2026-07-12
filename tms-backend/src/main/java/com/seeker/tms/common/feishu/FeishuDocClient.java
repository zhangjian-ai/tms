package com.seeker.tms.common.feishu;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.seeker.tms.common.config.FeishuProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 飞书文档内容客户端：docx 正文/标题、wiki 节点解析、电子表格读取。
 * 所有调用使用传入的 user_access_token（以用户身份访问受控文档）。
 */
@Slf4j
@Component
public class FeishuDocClient {

    /** 电子表格读取上限，避免超大表拖垮 */
    private static final int MAX_SHEET_ROWS = 500;
    private static final int MAX_SHEET_COLS = 50;

    private final FeishuProperties properties;
    private final OkHttpClient httpClient;

    public FeishuDocClient(FeishuProperties properties) {
        this.properties = properties;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    /** 新版文档 docx 纯文本正文 */
    public String getDocxRawContent(String docToken, String userToken) {
        JSONObject data = get("/open-apis/docx/v1/documents/" + docToken + "/raw_content", userToken);
        return data.getString("content");
    }

    /**
     * 结构化抓取新版文档 docx：一次遍历 blocks 得到正文文本、内嵌图片 file_token、引用链接。
     * withImages=true 时在正文对应位置插入占位符 {@code [[IMG:n]]}（n 与返回的 images 下标对齐），供上层做 OCR 回填。
     * blocks 接口异常时回退到 raw_content（纯文本，无图片/引用）。
     *
     * 注意：docx blocks 字段以飞书真实返回为准。
     */
    public DocxContent getDocxContent(String docToken, String userToken, boolean withImages) {
        try {
            // 拉取全部 block（翻页聚合）
            List<JSONObject> blocks = new ArrayList<>();
            String pageToken = null;
            do {
                String path = "/open-apis/docx/v1/documents/" + docToken + "/blocks?page_size=500";
                if (pageToken != null && !pageToken.isBlank()) {
                    path += "&page_token=" + URLEncoder.encode(pageToken, StandardCharsets.UTF_8);
                }
                JSONObject data = get(path, userToken);
                JSONArray items = data.getJSONArray("items");
                if (items != null) {
                    for (int i = 0; i < items.size(); i++) {
                        JSONObject b = items.getJSONObject(i);
                        if (b != null) blocks.add(b);
                    }
                }
                Boolean hasMore = data.getBoolean("has_more");
                pageToken = data.getString("page_token");
                if (hasMore == null || !hasMore) break;
            } while (pageToken != null && !pageToken.isBlank());

            // 建 block 索引并定位根(page)块
            Map<String, JSONObject> byId = new HashMap<>();
            JSONObject root = null;
            for (JSONObject b : blocks) {
                String id = b.getString("block_id");
                if (id != null) byId.put(id, b);
                Integer bt = b.getInteger("block_type");
                if (root == null && bt != null && bt == 1) root = b;
            }

            // 按块树渲染为 Markdown：保留标题层级、列表嵌套、引用、代码、表格与内嵌图片占位符
            if (log.isInfoEnabled()) {
                java.util.Map<Integer, Integer> typeCount = new java.util.TreeMap<>();
                for (JSONObject b : blocks) {
                    Integer typ = b.getInteger("block_type");
                    if (typ != null) typeCount.merge(typ, 1, Integer::sum);
                }
            }
            DocxRender render = new DocxRender(byId, withImages, userToken);
            String title = null;
            if (root != null) {
                JSONObject page = root.getJSONObject("page");
                if (page != null) {
                    title = render.renderInline(page.getJSONArray("elements")).trim();
                }
                render.renderChildren(root, 0);
            } else {
                // 兜底：无根块时只渲染顶层块（其子块随之递归），避免嵌套内容被重复平铺
                for (JSONObject b : blocks) {
                    String pid = b.getString("parent_id");
                    if (pid == null || !byId.containsKey(pid)) render.renderBlock(b, 0);
                }
            }

            if (title == null || title.isBlank()) {
                title = getDocxTitle(docToken, userToken);
            }
            String body = render.text().toString().replaceAll("\\n{3,}", "\n\n").trim();
            return new DocxContent(title, body, new ArrayList<>(render.refLinks()), render.images());
        } catch (Exception e) {
            String raw = getDocxRawContent(docToken, userToken);
            return new DocxContent(getDocxTitle(docToken, userToken), raw == null ? "" : raw,
                    new ArrayList<>(), new ArrayList<>());
        }
    }

    /** 下载飞书云文档媒体（图片等）字节；失败返回 null */
    public byte[] downloadMedia(String fileToken, String userToken) {
        Request request = new Request.Builder()
                .url(properties.getBaseUrl() + "/open-apis/drive/v1/medias/" + fileToken + "/download")
                .header("Authorization", "Bearer " + userToken)
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                return response.body().bytes();
            }
            String errBody = response.body() != null ? response.body().string() : "";
            log.warn("下载飞书媒体失败: {} status={} body={}", fileToken, response.code(), errBody);
        } catch (Exception e) {
            log.warn("下载飞书媒体异常: {}: {}", fileToken, e.toString());
        }
        return null;
    }

    /**
     * 把 docx blocks 树渲染为 Markdown：保留标题层级、列表嵌套、引用、代码、表格与内嵌图片占位符，
     * 并收集正文中的超链接/文档提及（供二级文档发现）。每次抓取新建实例，线程安全。
     */
    private class DocxRender {
        private final Map<String, JSONObject> byId;
        private final boolean withImages;
        private final String userToken;
        private final StringBuilder sb = new StringBuilder();
        private final List<ImageRef> images = new ArrayList<>();
        private final Set<String> refLinks = new LinkedHashSet<>();
        private final Set<String> visited = new HashSet<>();
        /** 标题自动编号计数器（下标 1..9 对应 heading1..9），飞书自动编号不在 block 文本中，需自行计算 */
        private final int[] headingCounters = new int[10];

        DocxRender(Map<String, JSONObject> byId, boolean withImages, String userToken) {
            this.byId = byId;
            this.withImages = withImages;
            this.userToken = userToken;
        }

        StringBuilder text() { return sb; }
        List<ImageRef> images() { return images; }
        Set<String> refLinks() { return refLinks; }

        void renderChildren(JSONObject block, int depth) {
            JSONArray children = block.getJSONArray("children");
            if (children == null) return;
            int orderedSeq = 0;
            for (int i = 0; i < children.size(); i++) {
                JSONObject child = byId.get(children.getString(i));
                if (child == null) continue;
                Integer ct = child.getInteger("block_type");
                if (ct != null && ct == 13) orderedSeq++; // 有序列表连续项递增
                else orderedSeq = 0;
                renderBlock(child, depth, orderedSeq);
            }
        }

        void renderBlock(JSONObject block, int depth) {
            renderBlock(block, depth, 1);
        }

        void renderBlock(JSONObject block, int depth, int ordinal) {
            String id = block.getString("block_id");
            if (id != null && !visited.add(id)) return; // 防环/重复
            Integer bt = block.getInteger("block_type");
            int t = bt == null ? -1 : bt;
            String indent = "  ".repeat(Math.max(0, depth));
            switch (t) {
                case 1: // page 根
                    renderChildren(block, depth);
                    break;
                case 2: { // 段落
                    String line = inlineOf(block, "text");
                    if (!line.isBlank()) sb.append(indent).append(line).append("\n\n");
                    renderChildren(block, depth);
                    break;
                }
                case 3: case 4: case 5: case 6: case 7:
                case 8: case 9: case 10: case 11: { // heading1..9
                    int level = t - 2;
                    sb.append("#".repeat(Math.min(level, 6))).append(' ')
                      .append(headingNumber(level))
                      .append(inlineOf(block, "heading" + level)).append("\n\n");
                    renderChildren(block, depth);
                    break;
                }
                case 12: // 无序列表
                    sb.append(indent).append("- ").append(inlineOf(block, "bullet")).append('\n');
                    renderChildren(block, depth + 1);
                    break;
                case 13: // 有序列表
                    sb.append(indent).append(ordinal > 0 ? ordinal : 1).append(". ")
                      .append(inlineOf(block, "ordered")).append('\n');
                    renderChildren(block, depth + 1);
                    break;
                case 17: { // 任务列表
                    JSONObject style = childStyle(block, "todo");
                    boolean done = style != null && Boolean.TRUE.equals(style.getBoolean("done"));
                    sb.append(indent).append(done ? "- [x] " : "- [ ] ")
                      .append(inlineOf(block, "todo")).append('\n');
                    renderChildren(block, depth + 1);
                    break;
                }
                case 14: // 代码块
                    sb.append("```\n").append(rawInlineOf(block, "code")).append("\n```\n\n");
                    break;
                case 15: // 引用
                    sb.append("> ").append(inlineOf(block, "quote")).append("\n\n");
                    renderChildren(block, depth);
                    break;
                case 22: // 分割线
                    sb.append("\n---\n\n");
                    break;
                case 27: { // 图片
                    JSONObject image = block.getJSONObject("image");
                    if (image != null) {
                        String ft = image.getString("token");
                        if (ft != null && !ft.isBlank()) {
                            if (withImages) sb.append("[[IMG:").append(images.size()).append("]]\n\n");
                            images.add(new ImageRef(ft));
                        }
                    }
                    break;
                }
                case 30: { // 内嵌电子表格：内容在独立表格对象里，按 sheet 接口只读所引用的那个 sheet
                    JSONObject sheet = block.getJSONObject("sheet");
                    String token = sheet != null ? sheet.getString("token") : null;
                    if (token != null && !token.isBlank()) {
                        // token 形如 {spreadsheetToken}_{sheetId}：拆出表格 token 与目标 sheetId
                        String ssToken = token;
                        String sheetId = null;
                        int us = token.indexOf('_');
                        if (us > 0) {
                            ssToken = token.substring(0, us);
                            sheetId = token.substring(us + 1);
                        }
                        try {
                            String sheetText = getSheetContent(ssToken, userToken, sheetId);
                            if (sheetText != null && !sheetText.isBlank()) sb.append(sheetText).append("\n\n");
                        } catch (Exception e) {
                            log.warn("内嵌电子表格解析失败: token={}: {}", token, e.toString());
                        }
                    }
                    break;
                }
                case 31: // 表格
                    renderTable(block);
                    break; // 单元格已在表内处理，不再递归
                default: {
                    // 其它类型（callout/table_cell/未知）：尽力渲染文本容器并递归子块
                    String line = anyInline(block);
                    if (line != null && !line.isBlank()) sb.append(indent).append(line).append("\n\n");
                    renderChildren(block, depth);
                }
            }
        }

        private void renderTable(JSONObject block) {
            JSONObject table = block.getJSONObject("table");
            if (table == null) { renderChildren(block, 0); return; }
            JSONObject prop = table.getJSONObject("property");
            JSONArray cells = table.getJSONArray("cells");
            int cols = prop != null ? prop.getIntValue("column_size") : 0;
            if (cells == null || cells.isEmpty() || cols <= 0) { renderChildren(block, 0); return; }

            List<String> texts = new ArrayList<>();
            for (int i = 0; i < cells.size(); i++) texts.add(cellText(cells.getString(i)));
            int rows = (int) Math.ceil((double) texts.size() / cols);
            List<List<String>> grid = new ArrayList<>();
            for (int r = 0; r < rows; r++) {
                List<String> row = new ArrayList<>();
                for (int c = 0; c < cols; c++) {
                    int idx = r * cols + c;
                    row.add(idx < texts.size() ? texts.get(idx) : "");
                }
                grid.add(row);
            }
            // 仅当飞书表格属性明确标记首行为表头时才作表头，不臆断
            boolean headerRow = prop != null && Boolean.TRUE.equals(prop.getBoolean("header_row"));
            appendMarkdownTable(sb, grid, headerRow);
        }

        private String cellText(String cellId) {
            JSONObject cell = byId.get(cellId);
            if (cell == null) return "";
            visited.add(cellId);
            List<String> parts = new ArrayList<>();
            collectCellText(cell, parts);
            return String.join("; ", parts).replace("|", "\\|").replace("\n", " ").trim();
        }

        /** 递归收集单元格内所有后代块的文本（含嵌套列表等），避免只取直接子块导致内容丢失 */
        private void collectCellText(JSONObject block, List<String> parts) {
            String own = anyInline(block);
            if (own != null && !own.isBlank()) parts.add(own.trim());
            JSONArray children = block.getJSONArray("children");
            if (children != null) {
                for (int i = 0; i < children.size(); i++) {
                    JSONObject cb = byId.get(children.getString(i));
                    if (cb == null) continue;
                    String cid = cb.getString("block_id");
                    if (cid != null) visited.add(cid);
                    collectCellText(cb, parts);
                }
            }
        }

        private JSONObject childStyle(JSONObject block, String field) {
            JSONObject c = block.getJSONObject(field);
            return c != null ? c.getJSONObject("style") : null;
        }

        /** 按标题层级生成自动编号前缀，如 "2.1.1 "（飞书自动编号不在文本中，需自行累计） */
        private String headingNumber(int level) {
            if (level < 1 || level >= headingCounters.length) return "";
            headingCounters[level]++;
            for (int i = level + 1; i < headingCounters.length; i++) headingCounters[i] = 0;
            for (int i = 1; i < level; i++) if (headingCounters[i] == 0) headingCounters[i] = 1;
            StringBuilder num = new StringBuilder();
            for (int i = 1; i <= level; i++) {
                if (i > 1) num.append('.');
                num.append(headingCounters[i]);
            }
            return num.append(' ').toString();
        }

        private String inlineOf(JSONObject block, String field) {
            JSONObject c = block.getJSONObject(field);
            return c != null ? renderInline(c.getJSONArray("elements")) : "";
        }

        private String anyInline(JSONObject block) {
            for (Map.Entry<String, Object> e : block.entrySet()) {
                if (e.getValue() instanceof JSONObject) {
                    JSONArray els = ((JSONObject) e.getValue()).getJSONArray("elements");
                    if (els != null) return renderInline(els);
                }
            }
            return "";
        }

        private String rawInlineOf(JSONObject block, String field) {
            JSONObject c = block.getJSONObject(field);
            if (c == null) return "";
            JSONArray els = c.getJSONArray("elements");
            if (els == null) return "";
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < els.size(); i++) {
                JSONObject el = els.getJSONObject(i);
                JSONObject tr = el != null ? el.getJSONObject("text_run") : null;
                if (tr != null && tr.getString("content") != null) s.append(tr.getString("content"));
            }
            return s.toString();
        }

        /** 渲染 elements 为带样式的 Markdown 文本，并收集其中的链接/文档提及 */
        String renderInline(JSONArray elements) {
            if (elements == null) return "";
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < elements.size(); i++) {
                JSONObject el = elements.getJSONObject(i);
                if (el == null) continue;
                JSONObject tr = el.getJSONObject("text_run");
                if (tr != null) {
                    String content = tr.getString("content");
                    if (content == null) content = "";
                    String url = null;
                    JSONObject style = tr.getJSONObject("text_element_style");
                    if (style != null) {
                        JSONObject link = style.getJSONObject("link");
                        if (link != null) { url = decode(link.getString("url")); collect(url); }
                    }
                    String deco = content;
                    if (!content.isBlank() && style != null) {
                        if (Boolean.TRUE.equals(style.getBoolean("inline_code"))) deco = "`" + deco + "`";
                        if (Boolean.TRUE.equals(style.getBoolean("bold"))) deco = "**" + deco + "**";
                        if (Boolean.TRUE.equals(style.getBoolean("italic"))) deco = "*" + deco + "*";
                    }
                    if (url != null && !url.isBlank() && !content.isBlank()) {
                        deco = "[" + deco + "](" + url + ")";
                    }
                    s.append(deco);
                    continue;
                }
                JSONObject md = el.getJSONObject("mention_doc");
                if (md != null) {
                    String title = md.getString("title");
                    String url = decode(md.getString("url"));
                    collect(url);
                    if (url != null && !url.isBlank()) {
                        s.append('[').append(title != null && !title.isBlank() ? title : url).append("](").append(url).append(')');
                    } else if (title != null) {
                        s.append(title);
                    }
                    continue;
                }
                JSONObject eq = el.getJSONObject("equation");
                if (eq != null && eq.getString("content") != null) s.append(eq.getString("content"));
            }
            return s.toString();
        }

        private void collect(String url) {
            if (url != null && !url.isBlank()) refLinks.add(url);
        }

        private String decode(String url) {
            if (url == null || url.isBlank()) return url;
            try {
                return URLDecoder.decode(url, StandardCharsets.UTF_8);
            } catch (Exception e) {
                return url;
            }
        }
    }

    /** 新版文档 docx 标题（失败返回 null） */
    public String getDocxTitle(String docToken, String userToken) {
        try {
            JSONObject data = get("/open-apis/docx/v1/documents/" + docToken, userToken);
            JSONObject doc = data.getJSONObject("document");
            return doc != null ? doc.getString("title") : null;
        } catch (Exception e) {
            log.warn("获取 docx 标题失败: {}: {}", docToken, e.toString());
            return null;
        }
    }

    /** 解析 wiki 节点，得到其挂载的实际文档 obj_type/obj_token/title */
    public WikiNode getWikiNode(String wikiToken, String userToken) {
        JSONObject data = get("/open-apis/wiki/v2/spaces/get_node?token=" + wikiToken, userToken);
        JSONObject node = data.getJSONObject("node");
        if (node == null) {
            throw new IllegalStateException("未能解析 wiki 节点: " + wikiToken);
        }
        return new WikiNode(node.getString("obj_type"), node.getString("obj_token"), node.getString("title"));
    }

    /** 电子表格：逐 sheet 读取并序列化为 Markdown 表格。元信息用 v2 metainfo，其 sheetId 与 v2 values 接口一致 */
    public String getSheetContent(String spreadsheetToken, String userToken) {
        return getSheetContent(spreadsheetToken, userToken, null);
    }

    /**
     * 读取电子表格内容。targetSheetId 非空时只读该 sheet（用于文档内嵌电子表格，token 形如
     * {spreadsheetToken}_{sheetId}），避免把整个表格簿的所有 sheet 都堆出来导致重复/串位；
     * 匹配不到时退回首个 sheet 并记录可用 sheetId 便于排查。
     */
    public String getSheetContent(String spreadsheetToken, String userToken, String targetSheetId) {
        JSONObject meta = get("/open-apis/sheets/v2/spreadsheets/" + spreadsheetToken + "/metainfo", userToken);
        JSONArray sheets = meta.getJSONArray("sheets");
        if (sheets == null || sheets.isEmpty()) {
            return "";
        }
        List<JSONObject> targets = new ArrayList<>();
        if (targetSheetId != null && !targetSheetId.isBlank()) {
            for (int i = 0; i < sheets.size(); i++) {
                JSONObject s = sheets.getJSONObject(i);
                if (targetSheetId.equals(s.getString("sheetId"))) { targets.add(s); break; }
            }
            if (targets.isEmpty()) {
                List<String> ids = new ArrayList<>();
                for (int i = 0; i < sheets.size(); i++) ids.add(sheets.getJSONObject(i).getString("sheetId"));
                log.warn("内嵌电子表格未匹配 sheetId={}，可用={}，退回首个 sheet", targetSheetId, ids);
                targets.add(sheets.getJSONObject(0));
            }
        } else {
            for (int i = 0; i < sheets.size(); i++) targets.add(sheets.getJSONObject(i));
        }

        StringBuilder sb = new StringBuilder();
        for (JSONObject sheet : targets) {
            String sheetId = sheet.getString("sheetId");
            int rows = Math.min(sheet.getIntValue("rowCount"), MAX_SHEET_ROWS);
            int cols = Math.min(sheet.getIntValue("columnCount"), MAX_SHEET_COLS);
            if (sheetId == null || rows <= 0 || cols <= 0) continue;
            try {
                String range = sheetId + "!A1:" + columnLetter(cols) + rows;
                String encodedRange = URLEncoder.encode(range, StandardCharsets.UTF_8);
                JSONObject valuesResp = get("/open-apis/sheets/v2/spreadsheets/" + spreadsheetToken
                        + "/values/" + encodedRange, userToken);
                JSONObject valueRange = valuesResp.getJSONObject("valueRange");
                JSONArray values = valueRange != null ? valueRange.getJSONArray("values") : null;

                appendSheetRows(sb, values);
                sb.append('\n');
            } catch (Exception e) {
                log.warn("读取电子表格 sheet 失败: token={}, sheetId={}: {}", spreadsheetToken, sheetId, e.toString());
            }
        }
        return sb.toString();
    }

    /** 读取表格 values 二维数组，渲染为标准 GFM Markdown 表格 */
    private void appendSheetRows(StringBuilder sb, JSONArray values) {
        if (values == null) return;
        List<List<String>> rows = new ArrayList<>();
        for (int r = 0; r < values.size(); r++) {
            JSONArray row = values.getJSONArray(r);
            if (row == null) continue;
            List<String> cells = new ArrayList<>();
            for (int c = 0; c < row.size(); c++) {
                cells.add(renderCell(row.get(c)).replace("|", "\\|").replace("\n", " ").trim());
            }
            rows.add(cells);
        }
        // 电子表格 values 接口不带表头信息，不臆断首行为表头
        appendMarkdownTable(sb, rows, false);
    }

    /**
     * 把二维文本渲染为表格文本，列数取最后一个非空列（裁掉尾部整列为空的列）。
     * 仅当明确知道首行是表头（firstRowIsHeader）时才输出标准 GFM 表格（表头 + `| --- |` 分隔行 + 数据行）；
     * 否则不臆造表头，直接按管道分隔逐行输出——既不误标数据行为表头，也不留无意义的空表头/分隔行。
     * 单元格需已完成转义（| 转义、换行转空格）。
     */
    private void appendMarkdownTable(StringBuilder sb, List<List<String>> rows, boolean firstRowIsHeader) {
        List<List<String>> data = new ArrayList<>();
        int lastCol = -1;
        for (List<String> r : rows) {
            if (r == null) continue;
            int rowLast = -1;
            for (int c = 0; c < r.size(); c++) {
                if (r.get(c) != null && !r.get(c).isBlank()) rowLast = c;
            }
            if (rowLast < 0) continue; // 整行为空，跳过
            data.add(r);
            lastCol = Math.max(lastCol, rowLast);
        }
        if (data.isEmpty() || lastCol < 0) return;
        int cols = lastCol + 1; // 去掉尾部整列为空的列，避免大量无意义空单元格噪声

        if (firstRowIsHeader) {
            appendTableRow(sb, data.get(0), cols);
            StringBuilder sep = new StringBuilder("|");
            for (int c = 0; c < cols; c++) sep.append(" --- |");
            sb.append(sep).append('\n');
            for (int r = 1; r < data.size(); r++) appendTableRow(sb, data.get(r), cols);
        } else {
            for (List<String> r : data) appendTableRow(sb, r, cols);
        }
        sb.append('\n');
    }

    /** 输出一行 Markdown 表格，列数不足以空单元格补齐 */
    private void appendTableRow(StringBuilder sb, List<String> row, int cols) {
        StringBuilder line = new StringBuilder("|");
        for (int c = 0; c < cols; c++) {
            String cell = (row != null && c < row.size() && row.get(c) != null) ? row.get(c) : "";
            line.append(' ').append(cell).append(" |");
        }
        sb.append(line).append('\n');
    }

    /** 单元格值渲染为文本（兼容富文本片段） */
    private String renderCell(Object cell) {
        if (cell == null) return "";
        if (cell instanceof String) return (String) cell;
        if (cell instanceof Number || cell instanceof Boolean) return String.valueOf(cell);
        // 富文本：飞书返回片段数组，尽力提取 text 字段
        if (cell instanceof JSONArray) {
            StringBuilder s = new StringBuilder();
            JSONArray arr = (JSONArray) cell;
            for (int i = 0; i < arr.size(); i++) {
                Object seg = arr.get(i);
                if (seg instanceof JSONObject) {
                    String t = ((JSONObject) seg).getString("text");
                    if (t != null) s.append(t);
                } else if (seg != null) {
                    s.append(seg);
                }
            }
            return s.toString();
        }
        return String.valueOf(cell);
    }

    /** 列号转 A1 字母，如 1->A, 27->AA */
    private String columnLetter(int col) {
        if (col <= 0) col = 1;
        StringBuilder s = new StringBuilder();
        while (col > 0) {
            int rem = (col - 1) % 26;
            s.insert(0, (char) ('A' + rem));
            col = (col - 1) / 26;
        }
        return s.toString();
    }

    /** GET 并校验飞书返回码，返回 data 节点 */
    private JSONObject get(String path, String userToken) {
        Request request = new Request.Builder()
                .url(properties.getBaseUrl() + path)
                .header("Authorization", "Bearer " + userToken)
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            JSONObject json = JSON.parseObject(respBody);
            if (json == null) {
                throw new RuntimeException("飞书文档接口返回为空: " + path);
            }
            Integer code = json.getInteger("code");
            if (code == null || code != 0) {
                throw new RuntimeException("飞书文档接口失败[" + path + "]: " + json.getString("msg") + " (code=" + code + ")");
            }
            JSONObject data = json.getJSONObject("data");
            return data != null ? data : new JSONObject();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("调用飞书文档接口异常: " + path, e);
        }
    }

    /** wiki 节点挂载的实际文档信息 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WikiNode {
        private String objType;
        private String objToken;
        private String title;
    }

    /** docx 结构化抓取结果：正文 + 内嵌图片 + 引用链接 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocxContent {
        private String title;
        private String text;
        /** 正文中出现的超链接/文档提及（已解码，未过滤类型） */
        private List<String> refLinks;
        /** 内嵌图片（与正文 [[IMG:n]] 占位符对齐） */
        private List<ImageRef> images;
    }

    /** 内嵌图片引用（飞书媒体 file_token） */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImageRef {
        private String fileToken;
    }
}
