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
 * 把用例树导出为 Excel（.xlsx）。列：
 * 用例目录 | 用例名称 | 需求ID | 前置条件 | 用例步骤 | 预期结果 | 用例类型 | 用例状态 | 用例等级 | 创建人。
 * 用例目录 = 从根节点到用例所属最末目录节点的标题，用「-」连接。
 */
@Slf4j
public final class CaseExcelBuilder {

    private CaseExcelBuilder() {}

    private static final String[] HEADERS = {
            "用例目录", "用例名称", "需求ID", "前置条件", "用例步骤", "预期结果", "用例类型", "用例状态", "用例等级", "创建人"
    };
    // 各列宽度（字符数）
    private static final int[] COLUMN_WIDTHS = {32, 30, 12, 40, 50, 50, 10, 10, 10, 12};

    public static byte[] build(XMindNode root, String creator) {
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
            collectRows(root, new ArrayList<>(), rows, creator == null ? "" : creator);

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

    /** 深度优先遍历：累积目录路径（root + 各级 module 标题），遇到用例即产出一行；跳过 free 自由节点子树 */
    private static void collectRows(XMindNode node, List<String> dirPath, List<String[]> rows, String creator) {
        if (node == null) return;
        String type = node.getType();
        if ("case".equals(type)) {
            rows.add(buildRow(node, String.join("-", dirPath), creator));
            return; // 用例的子节点是步骤，不再下钻
        }
        if ("free".equals(type)) return; // 自由节点及其子树不导出（与 XMind 导出一致）

        // root / module：把标题加入目录路径后递归
        List<String> nextPath = new ArrayList<>(dirPath);
        nextPath.add(node.getTitle() == null ? "" : node.getTitle());
        if (node.getChildren() != null) {
            for (XMindNode child : node.getChildren()) {
                collectRows(child, nextPath, rows, creator);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static String[] buildRow(XMindNode caseNode, String dir, String creator) {
        Map<String, Object> j = XMindTrees.caseNodeToJson(caseNode);
        String name = str(j.get("用例名称"));
        String level = str(j.get("优先级"));       // P0..P3（如实，不做进一步映射）
        String pre = str(j.get("前置条件"));        // 已去掉「前置条件:」前缀
        List<Map<String, String>> steps = (List<Map<String, String>>) j.get("测试步骤");

        StringBuilder stepSb = new StringBuilder();
        StringBuilder expSb = new StringBuilder();
        if (steps != null) {
            for (int i = 0; i < steps.size(); i++) {
                Map<String, String> s = steps.get(i);
                if (i > 0) {
                    stepSb.append('\n');
                    expSb.append('\n');
                }
                // 兼容处理用户直接把所有操作及结果写到一个节点内
                String action = s.get("执行操作");
                String result = s.get("预期结果");
                if (steps.size() == 1 && result.contains("预期结果")){
                    stepSb.append(action);
                    expSb.append(result);
                }
                else {
                    stepSb.append(numbered(action, i + 1));
                    expSb.append(numbered(result, i + 1));
                }
            }
        }

        return new String[]{
                dir, name, "", numberLines(pre), stepSb.toString(), expSb.toString(), "功能", "正常", level, creator
        };
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
