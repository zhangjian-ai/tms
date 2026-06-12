package com.seeker.tms.biz.confdiff.support;

import com.seeker.tms.biz.confdiff.entities.compare.ContentCompare.FileContentDiff;
import com.seeker.tms.biz.confdiff.entities.compare.ContentCompare.RowLine;
import com.seeker.tms.biz.confdiff.entities.compare.ContentCompare.SheetContentDiff;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Excel(.xls/.xlsx)行级对比:以基准侧(A)为基准,分析目标侧(B)缺少/多出的数据行。
 * 每个 sheet 的第一行作为表头(不参与数据行比对);数据行带真实 Excel 行号,
 * 按内容做多重集合差(同号同时出现在缺少/多出即为更新)。
 */
@Slf4j
public class ExcelComparator {

    /** 单元格分隔符(规范化整行用) */
    static final char CELL_SEP = '\u0001';

    private ExcelComparator() {}

    /** 单个 sheet 的解析结果:表头 + 数据行 */
    private static class SheetData {
        List<String> header = new ArrayList<>();
        List<RowLine> dataRows = new ArrayList<>();
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
            SheetContentDiff err = new SheetContentDiff(relPath, "<解析失败>");
            err.getRowsMissingInTarget().add(new RowLine(0, "[解析失败:" + e.getMessage() + "]"));
            result.getSheets().add(err);
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
            SheetContentDiff rowDiff = diffRows(relPath, sheet, a.dataRows, b.dataRows);
            if (!rowDiff.isEmpty()) {
                rowDiff.setHeaderA(a.header);
                rowDiff.setHeaderB(b.header);
                result.getSheets().add(rowDiff);
            }
        }
        return result;
    }

    private static SheetContentDiff diffRows(String file, String sheet, List<RowLine> rowsA, List<RowLine> rowsB) {
        SheetContentDiff diff = new SheetContentDiff(file, sheet);
        // 多重集合语义:按内容差集,保留重复行的计数差异,行号取各自真实行号
        List<String> bRemain = new ArrayList<>();
        for (RowLine r : rowsB) bRemain.add(r.getContent());
        for (RowLine r : rowsA) {
            if (!bRemain.remove(r.getContent())) {
                diff.getRowsMissingInTarget().add(r);
            }
        }
        List<String> aRemain = new ArrayList<>();
        for (RowLine r : rowsA) aRemain.add(r.getContent());
        for (RowLine r : rowsB) {
            if (!aRemain.remove(r.getContent())) {
                diff.getRowsExtraInTarget().add(r);
            }
        }
        return diff;
    }

    private static Map<String, SheetData> readSheets(File file) throws Exception {
        Map<String, SheetData> result = new LinkedHashMap<>();
        try (FileInputStream in = new FileInputStream(file);
             Workbook wb = WorkbookFactory.create(in)) {
            DataFormatter formatter = new DataFormatter();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                Sheet sheet = wb.getSheetAt(i);
                SheetData data = new SheetData();
                boolean headerTaken = false;
                for (Row row : sheet) {
                    String content = rowToString(row, formatter);
                    if (!headerTaken) {
                        // 第一行作为表头(拆成单元格列表),不计入数据行
                        data.header = splitCells(content);
                        headerTaken = true;
                        continue;
                    }
                    // 真实 Excel 行号(POI 行号从 0 起,+1 即可)
                    data.dataRows.add(new RowLine(row.getRowNum() + 1, content));
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

    private static String rowToString(Row row, DataFormatter formatter) {
        StringBuilder sb = new StringBuilder();
        short last = row.getLastCellNum();
        for (int c = 0; c < last; c++) {
            Cell cell = row.getCell(c);
            sb.append(cell == null ? "" : formatter.formatCellValue(cell).trim());
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
}
