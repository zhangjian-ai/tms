package com.seeker.tms.biz.testgen.service.impl;

import com.seeker.tms.biz.testgen.entities.XMindNode;
import com.seeker.tms.biz.testgen.utils.XMindTrees;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把用例树导出为 Excel（.xlsx）。列（共 7 列）：
 * 用例名称 | 用例分级 | 用例类型 | 前置条件 | 测试步骤 | 预期结果 | 所属目录。
 * 所属目录 = 从根节点到用例所属最末目录节点的标题，用「|」连接。
 * 多步骤用例按「一步一行」展开：首行携带名称/分级/类型/前置条件/所属目录，
 * 后续步骤行仅填 测试步骤 与 预期结果，其余列留空。
 */
@Slf4j
public final class CaseExcelBuilder {

    private CaseExcelBuilder() {}

    private static final String[] HEADERS = {
            "用例名称", "用例分级", "用例类型", "前置条件", "测试步骤", "预期结果", "所属目录"
    };
    // 各列宽度（字符数）
    private static final int[] COLUMN_WIDTHS = {34, 10, 12, 30, 46, 46, 40};
    // 目录层级连接符
    private static final String DIR_SEPARATOR = "|";

    public static byte[] build(XMindNode root) {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("测试用例");

            CellStyle headerStyle = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);
            headerStyle.setWrapText(true);
            headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);

            CellStyle bodyStyle = wb.createCellStyle();
            bodyStyle.setWrapText(true);
            bodyStyle.setVerticalAlignment(VerticalAlignment.TOP);

            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell c = header.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, COLUMN_WIDTHS[i] * 256);
            }

            List<String[]> rows = new ArrayList<>();
            collectRows(root, new ArrayList<>(), rows);

            int r = 1;
            for (String[] row : rows) {
                Row xr = sheet.createRow(r++);
                for (int i = 0; i < row.length; i++) {
                    Cell c = xr.createCell(i);
                    c.setCellValue(row[i]);
                    c.setCellStyle(bodyStyle);
                }
            }

            sheet.createFreezePane(0, 1);
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            log.error("构建用例 Excel 失败: {}", e.toString());
            throw new RuntimeException("导出用例 Excel 失败", e);
        }
    }

    /** 深度优先遍历：累积目录路径（root + 各级 module 标题），遇到用例即产出若干行；跳过 free 自由节点子树 */
    private static void collectRows(XMindNode node, List<String> dirPath, List<String[]> rows) {
        if (node == null) return;
        String type = node.getType();
        if ("case".equals(type)) {
            rows.addAll(buildRows(node, String.join(DIR_SEPARATOR, dirPath)));
            return; // 用例的子节点是步骤，不再下钻
        }
        if ("free".equals(type)) return; // 自由节点及其子树不导出（与 XMind 导出一致）

        // root / module：把标题加入目录路径后递归
        List<String> nextPath = new ArrayList<>(dirPath);
        nextPath.add(node.getTitle() == null ? "" : node.getTitle());
        if (node.getChildren() != null) {
            for (XMindNode child : node.getChildren()) {
                collectRows(child, nextPath, rows);
            }
        }
    }

    /**
     * 一条用例展开为「一步一行」：首行携带名称/分级/类型/前置条件/所属目录，
     * 后续步骤行仅填 测试步骤 与 预期结果，其余列留空。无步骤时产出单行（步骤/预期为空）。
     */
    @SuppressWarnings("unchecked")
    private static List<String[]> buildRows(XMindNode caseNode, String dir) {
        Map<String, Object> j = XMindTrees.caseNodeToJson(caseNode);
        String name = str(j.get("用例名称"));
        String level = str(j.get("优先级"));       // P0..P3（如实，不做进一步映射）
        String pre = numberLines(str(j.get("前置条件"))); // 已去掉「前置条件:」前缀
        List<Map<String, String>> steps = (List<Map<String, String>>) j.get("测试步骤");

        List<String[]> out = new ArrayList<>();
        if (steps == null || steps.isEmpty()) {
            out.add(new String[]{name, level, "功能", pre, "", "", dir});
            return out;
        }
        for (int i = 0; i < steps.size(); i++) {
            Map<String, String> s = steps.get(i);
            String action = str(s.get("执行操作"));
            String result = str(s.get("预期结果"));
            if (i == 0) {
                out.add(new String[]{name, level, "功能", pre, action, result, dir});
            } else {
                // 续行：仅步骤/预期，其余列留空
                out.add(new String[]{"", "", "", "", action, result, ""});
            }
        }
        return out;
    }

    /** 行首非数字则加「序号. 」前缀；空行不加序号。 */
    private static String numbered(String text, int seq) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return "";
        return Character.isDigit(t.charAt(0)) ? t : (seq + ". " + t);
    }

    /** 按换行拆分逐行编号（前置条件用）：多个条件各占一行，不以数字开头的行加「序号. 」前缀，序号仅对非空行递增。 */
    private static String numberLines(String text) {
        if (text == null || text.isBlank()) return "";
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int seq = 1;
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            sb.append(numbered(line, seq));
            seq++;
        }
        return sb.toString();
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
