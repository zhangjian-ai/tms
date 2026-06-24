package com.seeker.tms.biz.confdiff.support;

import com.seeker.tms.biz.confdiff.entities.compare.CompareResultVO;
import com.seeker.tms.biz.confdiff.entities.compare.ContentCompare;
import com.seeker.tms.biz.confdiff.entities.compare.ContentCompare.CellDiff;
import com.seeker.tms.biz.confdiff.entities.compare.ContentCompare.FileContentDiff;
import com.seeker.tms.biz.confdiff.entities.compare.ContentCompare.RowDiff;
import com.seeker.tms.biz.confdiff.entities.compare.ContentCompare.SheetContentDiff;
import com.seeker.tms.biz.confdiff.entities.compare.DirCompare;
import com.seeker.tms.biz.confdiff.entities.compare.FileCompare;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 生成 HTML 报告。按三大模块组织:目录对比、文件对比、文件内容对比。
 * 行差异以表格呈现,所有差异均以基准侧(A)为基准,描述目标侧(B)缺少/多出了什么。
 */
public class CompareReportWriter {

    private static final String ROOT = "(根目录)";

    private CompareReportWriter() {}

    public static byte[] write(CompareResultVO r) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">");
        sb.append("<title>配置对比报告</title>");
        sb.append(style());
        sb.append("</head><body>");

        sb.append("<h1>配置对比报告</h1>");
        sb.append("<table class=\"meta\">");
        metaRow(sb, "项目", esc(r.getProjectName()) + " (id=" + r.getProjectId() + ")");
        metaRow(sb, "基准侧 A", esc(r.getRefA()) + commitSuffix(r.getResolvedCommitA()));
        metaRow(sb, "目标侧 B", esc(r.getRefB()) + commitSuffix(r.getResolvedCommitB()));
        metaRow(sb, "结论", r.isConsistent()
                ? "<span class=\"ok\">完全一致</span>"
                : "<span class=\"bad\">存在差异</span>");
        metaRow(sb, "耗时", r.getElapsedMs() + " ms");
        if (r.getCreateTime() != null) metaRow(sb, "时间", esc(r.getCreateTime()));
        sb.append("</table>");
        sb.append("<p class=\"hint\">以下均以基准侧 A 为基准,描述目标侧 B 相对 A 缺少 / 多出了什么。</p>");

        writeDirModule(sb, r.getDirCompare());
        writeFileModule(sb, r.getFileCompare());
        writeContentModule(sb, r.getContentCompare());

        sb.append("</body></html>");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 模块一:目录对比 */
    private static void writeDirModule(StringBuilder sb, DirCompare dir) {
        sb.append("<h2>模块一 · 目录对比</h2>");
        if (dir.isEmpty()) {
            sb.append(okBlock("目录结构一致,无差异。"));
            return;
        }
        dirTable(sb, "目标侧缺少的目录", "missing", dir.getMissingInTarget());
        dirTable(sb, "目标侧多出的目录", "extra", dir.getExtraInTarget());
    }

    private static void dirTable(StringBuilder sb, String title, String cls, List<DirCompare.DirEntry> entries) {
        if (entries == null || entries.isEmpty()) return;
        sb.append(blockTitle(cls, title, entries.size()));
        sb.append("<table class=\"diff\"><thead><tr><th>目录</th><th>父目录</th></tr></thead><tbody>");
        for (DirCompare.DirEntry e : entries) {
            sb.append("<tr><td>").append(esc(e.getPath())).append("</td><td>")
                    .append(esc(orRoot(e.getParent()))).append("</td></tr>");
        }
        sb.append("</tbody></table>");
    }

    /** 模块二:文件对比 */
    private static void writeFileModule(StringBuilder sb, FileCompare file) {
        sb.append("<h2>模块二 · 文件对比</h2>");
        if (file.isEmpty()) {
            sb.append(okBlock("文件清单一致,无差异。"));
            return;
        }
        fileTable(sb, "目标侧缺少的文件", "missing", file.getMissingInTarget());
        fileTable(sb, "目标侧多出的文件", "extra", file.getExtraInTarget());
        contentChangedTable(sb, file.getContentChanged());
    }

    private static void fileTable(StringBuilder sb, String title, String cls, List<FileCompare.FileEntry> entries) {
        if (entries == null || entries.isEmpty()) return;
        sb.append(blockTitle(cls, title, entries.size()));
        sb.append("<table class=\"diff\"><thead><tr><th>文件</th><th>所在目录</th></tr></thead><tbody>");
        for (FileCompare.FileEntry e : entries) {
            sb.append("<tr><td>").append(esc(baseName(e.getPath()))).append("</td><td>")
                    .append(esc(orRoot(e.getDir()))).append("</td></tr>");
        }
        sb.append("</tbody></table>");
    }

    /** 内容不同的文件(非Excel,如 .bytes 二进制):展示两侧 md5 */
    private static void contentChangedTable(StringBuilder sb, List<FileCompare.FileEntry> entries) {
        if (entries == null || entries.isEmpty()) return;
        sb.append(blockTitle("changed", "内容不同的文件(非Excel,按md5对比)", entries.size()));
        sb.append("<table class=\"diff\"><thead><tr><th>文件</th><th>所在目录</th><th>基准侧 A md5</th><th>目标侧 B md5</th></tr></thead><tbody>");
        for (FileCompare.FileEntry e : entries) {
            sb.append("<tr><td>").append(esc(baseName(e.getPath()))).append("</td><td>")
                    .append(esc(orRoot(e.getDir()))).append("</td>")
                    .append("<td class=\"md5\">").append(esc(e.getMd5A())).append("</td>")
                    .append("<td class=\"md5\">").append(esc(e.getMd5B())).append("</td></tr>");
        }
        sb.append("</tbody></table>");
    }

    /** 取文件名(去掉相对目录部分) */
    private static String baseName(String path) {
        if (path == null) return "";
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    /** 模块三:文件内容对比(Excel 数据行,细化到单元格)。按文件维度默认折叠 */
    private static void writeContentModule(StringBuilder sb, ContentCompare content) {
        sb.append("<h2>模块三 · 文件内容对比(Excel 数据行)</h2>");
        if (content.isEmpty()) {
            sb.append(okBlock("文件内容一致,无差异。"));
            return;
        }
        sb.append("<p class=\"hint\">每个文件默认折叠,点击文件名展开查看明细。更新行单元格内呈现 旧值 → 新值。删除行的行号为该行在基准文件(A)中的行号。</p>");
        for (FileContentDiff fd : content.getFiles()) {
            if (fd.isEmpty()) continue;
            // <details> 默认折叠(不加 open)
            sb.append("<details class=\"file-details\"><summary>📄 ")
                    .append(esc(fd.getPath()))
                    .append("<span class=\"summary\">").append(esc(fileSummary(fd))).append("</span>")
                    .append("</summary>");
            if (fd.getError() != null) {
                sb.append("<p class=\"sheets missing\">").append(esc(fd.getError())).append("</p>");
            }
            if (!fd.getSheetsMissingInTarget().isEmpty()) {
                sb.append("<p class=\"sheets missing\">目标侧缺少的 sheet：")
                        .append(esc(String.join("、", fd.getSheetsMissingInTarget()))).append("</p>");
            }
            if (!fd.getSheetsExtraInTarget().isEmpty()) {
                sb.append("<p class=\"sheets extra\">目标侧多出的 sheet：")
                        .append(esc(String.join("、", fd.getSheetsExtraInTarget()))).append("</p>");
            }
            for (SheetContentDiff sd : fd.getSheets()) {
                if (sd.isEmpty()) continue;
                sb.append("<h4 class=\"sheet\">sheet：").append(esc(sd.getSheet())).append("</h4>");
                rowTable(sb, sd);
            }
            sb.append("</details>");
        }
    }

    /** 文件差异摘要:缺少/多出 sheet 数、新增/删除/更新行数 */
    private static String fileSummary(FileContentDiff fd) {
        int added = 0;
        int deleted = 0;
        int updated = 0;
        for (SheetContentDiff sd : fd.getSheets()) {
            for (RowDiff r : sd.getRows()) {
                if (ContentCompare.ADDED.equals(r.getStatus())) added++;
                else if (ContentCompare.DELETED.equals(r.getStatus())) deleted++;
                else updated++;
            }
        }
        StringBuilder s = new StringBuilder();
        if (!fd.getSheetsMissingInTarget().isEmpty()) s.append("缺少 sheet ").append(fd.getSheetsMissingInTarget().size()).append(" · ");
        if (!fd.getSheetsExtraInTarget().isEmpty()) s.append("多出 sheet ").append(fd.getSheetsExtraInTarget().size()).append(" · ");
        if (added > 0) s.append("新增 ").append(added).append(" 行 · ");
        if (deleted > 0) s.append("删除 ").append(deleted).append(" 行 · ");
        if (updated > 0) s.append("更新 ").append(updated).append(" 行 · ");
        String out = s.toString();
        return out.endsWith(" · ") ? out.substring(0, out.length() - 3) : out;
    }

    /** 单 sheet 一张表:状态 | 行号 | 各列;更新单元格呈现 旧值 → 新值。展示全部差异行,不省略 */
    private static void rowTable(StringBuilder sb, SheetContentDiff sd) {
        List<RowDiff> rows = sd.getRows();
        if (rows == null || rows.isEmpty()) return;
        List<String> headers = sd.getHeader();
        int maxCols = headers == null ? 0 : headers.size();
        for (RowDiff r : rows) {
            maxCols = Math.max(maxCols, r.getCells().size());
        }
        sb.append("<table class=\"diff rows\"><thead><tr><th>状态</th><th>行号</th>");
        for (int c = 0; c < maxCols; c++) {
            String h = (headers != null && c < headers.size() && !headers.get(c).isEmpty())
                    ? headers.get(c) : ("列" + (c + 1));
            sb.append("<th>").append(esc(h)).append("</th>");
        }
        sb.append("</tr></thead><tbody>");
        for (RowDiff row : rows) {
            String stCls;
            String stLabel;
            if (ContentCompare.ADDED.equals(row.getStatus())) { stCls = "st-add"; stLabel = "新增"; }
            else if (ContentCompare.DELETED.equals(row.getStatus())) { stCls = "st-del"; stLabel = "删除"; }
            else { stCls = "st-upd"; stLabel = "更新"; }
            sb.append("<tr><td class=\"st ").append(stCls).append("\">").append(stLabel).append("</td>");
            sb.append("<td class=\"idx\">").append(row.getRowNum()).append("</td>");
            List<CellDiff> cells = row.getCells();
            for (int c = 0; c < maxCols; c++) {
                sb.append("<td>").append(renderCell(row.getStatus(), c < cells.size() ? cells.get(c) : null)).append("</td>");
            }
            sb.append("</tr>");
        }
        sb.append("</tbody></table>");
    }

    /** 单元格渲染:新增取 b,删除取 a,更新且变化呈现 旧 → 新,否则取值 */
    private static String renderCell(String status, CellDiff cell) {
        if (cell == null) return "";
        if (ContentCompare.ADDED.equals(status)) return esc(cell.getB());
        if (ContentCompare.DELETED.equals(status)) return esc(cell.getA());
        // UPDATED
        if (cell.isChanged()) {
            return "<span class=\"old\">" + esc(cell.getA()) + "</span>"
                    + "<span class=\"arrow\"> → </span>"
                    + "<span class=\"new\">" + esc(cell.getB()) + "</span>";
        }
        return esc(cell.getB());
    }

    private static void metaRow(StringBuilder sb, String k, String v) {
        sb.append("<tr><th>").append(esc(k)).append("</th><td>").append(v).append("</td></tr>");
    }

    private static String blockTitle(String cls, String title, int count) {
        String sign = "missing".equals(cls) ? "−" : "extra".equals(cls) ? "＋" : "≠";
        return "<div class=\"block-title " + cls + "\">" + sign + " " + esc(title) + " (" + count + ")</div>";
    }

    private static String okBlock(String msg) {
        return "<p class=\"ok-block\">✓ " + esc(msg) + "</p>";
    }

    private static String commitSuffix(String commit) {
        return commit == null ? "" : " <span class=\"commit\">[" + esc(commit) + "]</span>";
    }

    private static String orRoot(String s) {
        return s == null || s.isEmpty() ? ROOT : s;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String style() {
        return "<style>"
                + "body{font-family:-apple-system,'Segoe UI',Roboto,'PingFang SC','Microsoft YaHei',sans-serif;margin:24px;color:#303133;background:#f7f8fa;}"
                + "h1{font-size:22px;}h2{margin-top:28px;padding:8px 12px;background:#304156;color:#fff;border-radius:4px;font-size:16px;}"
                + "h3.file{margin:16px 0 6px;font-size:15px;}h4.sheet{margin:10px 0 4px;color:#606266;font-size:14px;}"
                + "table{border-collapse:collapse;background:#fff;margin:6px 0 14px;}"
                + "table.meta th{background:#f5f7fa;text-align:right;width:110px;}"
                + "table.diff{width:100%;}"
                + "th,td{border:1px solid #ebeef5;padding:5px 10px;font-size:13px;text-align:left;vertical-align:top;}"
                + "thead th{background:#fafafa;}"
                + "td.idx{color:#c0c4cc;width:40px;text-align:right;}"
                + ".block-title{font-weight:600;margin:10px 0 4px;}"
                + ".block-title.missing{color:#e6a23c;}.block-title.extra{color:#67c23a;}.block-title.changed{color:#f56c6c;}"
                + ".ok,.ok-block{color:#67c23a;}.bad{color:#f56c6c;font-weight:600;}"
                + ".hint{color:#909399;font-size:13px;}.commit{color:#909399;font-family:monospace;font-size:12px;}"
                + ".sheets.missing{color:#e6a23c;}.sheets.extra{color:#67c23a;}.more{color:#909399;font-size:12px;}"
                + "table.rows td{font-family:monospace;}"
                + "table.rows thead th{text-align:center;}"
                + "td.st{font-family:inherit;font-weight:600;text-align:center;white-space:nowrap;}"
                + "td.st-del{color:#e6a23c;}td.st-add{color:#67c23a;}td.st-upd{color:#409eff;}"
                + "td.md5{font-family:monospace;font-size:12px;color:#909399;}"
                + ".old{color:#f56c6c;text-decoration:line-through;}.new{color:#67c23a;}.arrow{color:#909399;}"
                + "details.file-details{background:#fff;border:1px solid #ebeef5;border-radius:4px;margin:8px 0;padding:6px 12px;}"
                + "details.file-details>summary{cursor:pointer;font-weight:600;font-size:15px;list-style:revert;}"
                + "details.file-details>summary .summary{margin-left:12px;color:#909399;font-weight:400;font-size:12px;}"
                + "details[open]>summary{margin-bottom:8px;border-bottom:1px solid #f0f0f0;padding-bottom:6px;}"
                + "</style>";
    }
}
