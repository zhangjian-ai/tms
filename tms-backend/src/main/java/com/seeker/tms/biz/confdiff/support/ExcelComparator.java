package com.seeker.tms.biz.confdiff.support;

import com.seeker.tms.biz.confdiff.entities.compare.ContentCompare;
import com.seeker.tms.biz.confdiff.entities.compare.ContentCompare.CellDiff;
import com.seeker.tms.biz.confdiff.entities.compare.ContentCompare.FileContentDiff;
import com.seeker.tms.biz.confdiff.entities.compare.ContentCompare.RowDiff;
import com.seeker.tms.biz.confdiff.entities.compare.ContentCompare.SheetContentDiff;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Excel(.xls/.xlsx)行级 + 单元格级对比:以基准侧(A)为基准,目标侧(B)的行变化:
 * 新增(ADDED)/删除(DELETED)/更新(UPDATED);更新行细化到单元格 a -> b。
 * 每个 sheet 的第一行作为表头(不参与数据行比对);数据行带真实 Excel 行号。
 * 先按内容做多重集合差找出"仅A/仅B"的行,再按行号将同号的一对配成"更新"。
 */
@Slf4j
public class ExcelComparator {

    /** 单元格分隔符(规范化整行用) */
    static final char CELL_SEP = '\u0001';

    private ExcelComparator() {}

    /** 一行原始数据:真实行号 + 整行内容 */
    private static class RawRow {
        final int rowNum;
        final String content;
        RawRow(int rowNum, String content) {
            this.rowNum = rowNum;
            this.content = content;
        }
    }

    /** 单个 sheet 的解析结果:表头 + 数据行 */
    private static class SheetData {
        List<String> header = new ArrayList<>();
        List<RawRow> dataRows = new ArrayList<>();
    }

    /**
     * 对比两个 excel 文件。解析异常时返回带提示的差异(不中断整体对比)。
     *
     * @param relPath 文件相对路径,用于标识
     */
    public static FileContentDiff diff(File fileA, File fileB, String relPath) {
        FileContentDiff result = new FileContentDiff(relPath);
        Map<String, SheetData> sheetsA;
        Map<String, SheetData> sheetsB;
        try {
            sheetsA = readSheets(fileA);
            sheetsB = readSheets(fileB);
        } catch (Exception e) {
            log.warn("解析 excel 失败,跳过行级对比: {} | {}", relPath, e.getMessage());
            result.setError("解析失败:" + e.getMessage());
            return result;
        }

        // sheet 维度:目标侧缺少/多出
        Set<String> missing = new TreeSet<>(sheetsA.keySet());
        missing.removeAll(sheetsB.keySet());
        Set<String> extra = new TreeSet<>(sheetsB.keySet());
        extra.removeAll(sheetsA.keySet());
        result.setSheetsMissingInTarget(new ArrayList<>(missing));
        result.setSheetsExtraInTarget(new ArrayList<>(extra));

        // 共有 sheet 的行差异
        for (String sheet : sheetsA.keySet()) {
            if (!sheetsB.containsKey(sheet)) continue;
            SheetData a = sheetsA.get(sheet);
            SheetData b = sheetsB.get(sheet);
            SheetContentDiff sd = diffSheet(relPath, sheet, a, b);
            if (!sd.isEmpty()) {
                result.getSheets().add(sd);
            }
        }
        return result;
    }

    /** 相似度阈值:残差行之间同列同值比例 >= 该值才视为"同一行被修改"(更新),否则按删除+新增 */
    private static final double UPDATE_SIM_THRESHOLD = 0.5;
    /** 残差两两配对规模上限,超过则跳过相似度配对(全部按删除/新增),避免开销过大 */
    private static final long PAIR_LIMIT = 1_000_000L;

    /**
     * 行对齐(不依赖任何主键列,也不依赖行位置):
     * 1) 多重集合:内容完全相同的行视为"未变"(无论是否重排序),从两侧成对消除;
     * 2) 残差行(仅基准侧=删除候选,仅目标侧=新增候选)按"同列同值的单元格比例"配对,
     *    相似度达阈值的一对视为"同一行被修改"(更新,细化到单元格),其余为纯删除/新增。
     * 兼顾整块插入/删除、行重排序与行内修改。
     */
    private static SheetContentDiff diffSheet(String file, String sheet, SheetData a, SheetData b) {
        SheetContentDiff sd = new SheetContentDiff(file, sheet);
        sd.setHeader(b.header.isEmpty() ? a.header : b.header);

        // 1. 多重集合消除"内容完全相同"的行,得到两侧残差
        Map<String, Integer> bCounts = countByContent(b.dataRows);
        List<RawRow> aOnly = new ArrayList<>();
        for (RawRow r : a.dataRows) {
            Integer c = bCounts.get(r.content);
            if (c != null && c > 0) bCounts.put(r.content, c - 1);
            else aOnly.add(r);
        }
        Map<String, Integer> aCounts = countByContent(a.dataRows);
        List<RawRow> bOnly = new ArrayList<>();
        for (RawRow r : b.dataRows) {
            Integer c = aCounts.get(r.content);
            if (c != null && c > 0) aCounts.put(r.content, c - 1);
            else bOnly.add(r);
        }

        // 2. 残差按相似度配对为"更新"
        boolean[] aUsed = new boolean[aOnly.size()];
        boolean[] bUsed = new boolean[bOnly.size()];
        if ((long) aOnly.size() * bOnly.size() <= PAIR_LIMIT) {
            List<double[]> scored = new ArrayList<>(); // {score, i, j}
            for (int i = 0; i < aOnly.size(); i++) {
                for (int j = 0; j < bOnly.size(); j++) {
                    double s = similarity(aOnly.get(i).content, bOnly.get(j).content);
                    if (s >= UPDATE_SIM_THRESHOLD) scored.add(new double[]{s, i, j});
                }
            }
            scored.sort((x, y) -> Double.compare(y[0], x[0])); // 相似度降序贪心配对
            for (double[] sc : scored) {
                int i = (int) sc[1];
                int j = (int) sc[2];
                if (aUsed[i] || bUsed[j]) continue;
                aUsed[i] = true;
                bUsed[j] = true;
                sd.getRows().add(buildUpdated(bOnly.get(j).rowNum, aOnly.get(i).content, bOnly.get(j).content));
            }
        }

        // 3. 未配对残差 -> 删除 / 新增
        for (int i = 0; i < aOnly.size(); i++) {
            if (!aUsed[i]) {
                sd.getRows().add(buildSingle(aOnly.get(i).rowNum, ContentCompare.DELETED, splitCells(aOnly.get(i).content), true));
            }
        }
        for (int j = 0; j < bOnly.size(); j++) {
            if (!bUsed[j]) {
                sd.getRows().add(buildSingle(bOnly.get(j).rowNum, ContentCompare.ADDED, splitCells(bOnly.get(j).content), false));
            }
        }

        // 4. 按行号排序展示
        sd.getRows().sort(Comparator.comparingInt(RowDiff::getRowNum));
        return sd;
    }

    private static Map<String, Integer> countByContent(List<RawRow> rows) {
        Map<String, Integer> m = new HashMap<>();
        for (RawRow r : rows) m.merge(r.content, 1, Integer::sum);
        return m;
    }

    /** 两行同列同值的单元格比例 */
    private static double similarity(String aContent, String bContent) {
        List<String> ca = splitCells(aContent);
        List<String> cb = splitCells(bContent);
        int cols = Math.max(ca.size(), cb.size());
        if (cols == 0) return 1.0;
        int eq = 0;
        for (int i = 0; i < cols; i++) {
            String va = i < ca.size() ? ca.get(i) : "";
            String vb = i < cb.size() ? cb.get(i) : "";
            if (va.equals(vb)) eq++;
        }
        return (double) eq / cols;
    }

    private static RowDiff buildUpdated(int rowNum, String aContent, String bContent) {
        List<String> ca = splitCells(aContent);
        List<String> cb = splitCells(bContent);
        int cols = Math.max(ca.size(), cb.size());
        RowDiff rd = new RowDiff(rowNum, ContentCompare.UPDATED);
        for (int i = 0; i < cols; i++) {
            String va = i < ca.size() ? ca.get(i) : "";
            String vb = i < cb.size() ? cb.get(i) : "";
            rd.getCells().add(new CellDiff(va, vb, !va.equals(vb)));
        }
        return rd;
    }

    /** 删除行(用 a 值)或新增行(用 b 值) */
    private static RowDiff buildSingle(int rowNum, String status, List<String> values, boolean isA) {
        RowDiff rd = new RowDiff(rowNum, status);
        for (String v : values) {
            rd.getCells().add(isA ? new CellDiff(v, "", false) : new CellDiff("", v, false));
        }
        return rd;
    }

    private static Map<String, SheetData> readSheets(File file) throws Exception {
        Map<String, SheetData> result = new LinkedHashMap<>();
        try (FileInputStream in = new FileInputStream(file);
             Workbook wb = WorkbookFactory.create(in)) {
            DataFormatter formatter = new DataFormatter();
            // 公式单元格取计算值而非公式文本(相对引用会随行号变动,否则插入行会让后续行全部"变化")
            FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();
            evaluator.setIgnoreMissingWorkbooks(true); // 外部链接(如 SharePoint)缺失时忽略
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                Sheet sheet = wb.getSheetAt(i);
                SheetData data = new SheetData();
                boolean headerTaken = false;
                for (Row row : sheet) {
                    String content = rowToString(row, formatter, evaluator);
                    if (!headerTaken) {
                        data.header = splitCells(content);
                        headerTaken = true;
                        continue;
                    }
                    data.dataRows.add(new RawRow(row.getRowNum() + 1, content));
                }
                result.put(sheet.getSheetName(), data);
            }
        }
        return result;
    }

    private static List<String> splitCells(String content) {
        if (content == null || content.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(content.split(String.valueOf(CELL_SEP), -1)));
    }

    private static String rowToString(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
        StringBuilder sb = new StringBuilder();
        short last = row.getLastCellNum();
        for (int c = 0; c < last; c++) {
            sb.append(cellText(row.getCell(c), formatter, evaluator));
            sb.append(CELL_SEP);
        }
        // 去掉尾部空单元格,避免行尾空列影响比对
        String s = sb.toString();
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == CELL_SEP) {
            end--;
        }
        return s.substring(0, end);
    }

    /** 取单元格文本:公式单元格取计算值;计算失败(如外部链接)回退到原始格式化 */
    private static String cellText(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null) return "";
        try {
            return formatter.formatCellValue(cell, evaluator).trim();
        } catch (Exception e) {
            try {
                return formatter.formatCellValue(cell).trim();
            } catch (Exception e2) {
                return "";
            }
        }
    }
}
