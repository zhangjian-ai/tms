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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    /** 候选倒排表上限:某(列#值)对应的目标行超过此数视为非区分性,不用于找候选 */
    private static final int CANDIDATE_POSTING_CAP = 2000;

    /**
     * 行对齐(不依赖任何主键列,也不依赖行位置/顺序):
     * 1) 多重集合:内容完全相同的行视为"未变"(无论是否重排序),从两侧成对消除;
     * 2) 残差行(仅基准侧=删除候选,仅目标侧=新增候选)做"与顺序无关的全局相似度匹配":
     *    用倒排索引(列#值)快速找候选,按相似度贪心配对,匹配上的视为"同一行被修改"(更新),
     *    未匹配的为纯删除/新增。
     * 兼顾整块插入/删除、行重排序、行内修改;大量行被修改也不会退化成删除+新增级联。
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

        // 2. 残差对齐:主键在全量数据上识别,有键按键对齐,否则全局相似度匹配;匹配->更新,未匹配->删除/新增
        // updatedAtoB 记录"更新行"的 A 行号 -> B 行号,供删除行定位到 B 坐标系使用
        Map<Integer, Integer> updatedAtoB = new HashMap<>();
        alignResiduals(sd, aOnly, bOnly, a, b, updatedAtoB);

        // 3. 统一到 B 坐标系排序:删除行没有 B 行号,锚定到"前一个仍存在于 B 的行",避免沿用 A 行号造成错位/滞后
        sortByBPosition(sd, a, b, aOnly, bOnly, updatedAtoB);
        return sd;
    }

    /**
     * 残差对齐:优先按"自动识别的主键列"对齐(应对行重排序/乱序,避免按非键列错配);
     * 识别不出主键列时,退化为与顺序无关的全局相似度匹配。
     * 主键列在【全量数据行 a/b】上识别,而非仅残差:残差过小时,非键列(甚至每行都被改过的列)
     * 也可能"恰好唯一"而被误判为主键,导致本应是更新的行被错配,或退化成删除+新增。
     */
    private static void alignResiduals(SheetContentDiff sd, List<RawRow> aOnly, List<RawRow> bOnly,
                                       SheetData a, SheetData b, Map<Integer, Integer> updatedAtoB) {
        if (aOnly.isEmpty() && bOnly.isEmpty()) return;
        if (aOnly.isEmpty()) { for (RawRow r : bOnly) addRow(sd, r, ContentCompare.ADDED); return; }
        if (bOnly.isEmpty()) { for (RawRow r : aOnly) addRow(sd, r, ContentCompare.DELETED); return; }

        int keyCol = detectKeyColumn(splitAll(a.dataRows), splitAll(b.dataRows));
        if (keyCol >= 0) {
            alignByKey(sd, aOnly, bOnly, splitAll(aOnly), splitAll(bOnly), keyCol, updatedAtoB);
        } else {
            alignBySimilarity(sd, aOnly, bOnly, updatedAtoB);
        }
    }

    /** 按主键列对齐:同键->更新(内容不同时),仅基准侧->删除,仅目标侧->新增;空键行用相似度兜底 */
    private static void alignByKey(SheetContentDiff sd, List<RawRow> aOnly, List<RawRow> bOnly,
                                   List<String[]> aCells, List<String[]> bCells, int keyCol,
                                   Map<Integer, Integer> updatedAtoB) {
        Map<String, List<RawRow>> aMap = new LinkedHashMap<>();
        List<RawRow> aNoKey = new ArrayList<>();
        for (int i = 0; i < aOnly.size(); i++) {
            String k = cellAt(aCells.get(i), keyCol);
            if (k.isEmpty()) aNoKey.add(aOnly.get(i));
            else aMap.computeIfAbsent(k, x -> new ArrayList<>()).add(aOnly.get(i));
        }
        Map<String, List<RawRow>> bMap = new LinkedHashMap<>();
        List<RawRow> bNoKey = new ArrayList<>();
        for (int j = 0; j < bOnly.size(); j++) {
            String k = cellAt(bCells.get(j), keyCol);
            if (k.isEmpty()) bNoKey.add(bOnly.get(j));
            else bMap.computeIfAbsent(k, x -> new ArrayList<>()).add(bOnly.get(j));
        }
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(aMap.keySet());
        keys.addAll(bMap.keySet());
        for (String k : keys) {
            List<RawRow> la = aMap.getOrDefault(k, java.util.Collections.emptyList());
            List<RawRow> lb = bMap.getOrDefault(k, java.util.Collections.emptyList());
            int z = Math.min(la.size(), lb.size());
            for (int t = 0; t < z; t++) {
                RawRow ra = la.get(t);
                RawRow rb = lb.get(t);
                if (!ra.content.equals(rb.content)) {
                    sd.getRows().add(buildUpdated(rb.rowNum, ra.content, rb.content));
                    updatedAtoB.put(ra.rowNum, rb.rowNum);
                }
            }
            for (int t = z; t < la.size(); t++) addRow(sd, la.get(t), ContentCompare.DELETED);
            for (int t = z; t < lb.size(); t++) addRow(sd, lb.get(t), ContentCompare.ADDED);
        }
        if (!aNoKey.isEmpty() || !bNoKey.isEmpty()) alignBySimilarity(sd, aNoKey, bNoKey, updatedAtoB);
    }

    /**
     * 自动识别主键列:在两侧全量数据行中都"基本非空(>=90%)且基本唯一(>=90%)"的列,取区分度最高者;无则 -1。
     */
    private static int detectKeyColumn(List<String[]> a, List<String[]> b) {
        int maxCols = 0;
        for (String[] r : a) maxCols = Math.max(maxCols, r.length);
        for (String[] r : b) maxCols = Math.max(maxCols, r.length);
        int bestCol = -1;
        int bestScore = -1;
        for (int c = 0; c < maxCols; c++) {
            int da = keyDistinct(a, c);
            int db = keyDistinct(b, c);
            if (da < 0 || db < 0) continue;
            int score = da + db;
            if (score > bestScore) { bestScore = score; bestCol = c; }
        }
        return bestCol;
    }

    /** 该列在列表中"基本非空且基本唯一"时返回 distinct 数,否则 -1 */
    private static int keyDistinct(List<String[]> rows, int c) {
        if (rows.isEmpty()) return -1;
        int nonEmpty = 0;
        Set<String> distinct = new HashSet<>();
        for (String[] r : rows) {
            String v = cellAt(r, c);
            if (!v.isEmpty()) {
                nonEmpty++;
                distinct.add(v);
            }
        }
        if (nonEmpty < rows.size() * 0.9) return -1;
        if (distinct.size() < nonEmpty * 0.9) return -1;
        return distinct.size();
    }

    private static List<String[]> splitAll(List<RawRow> rows) {
        List<String[]> out = new ArrayList<>(rows.size());
        for (RawRow r : rows) out.add(splitArr(r.content));
        return out;
    }

    private static String cellAt(String[] cells, int c) {
        return c < cells.length ? cells[c] : "";
    }

    /** 与顺序无关的全局相似度匹配(倒排索引找候选 + 按相似度贪心配对) */
    private static void alignBySimilarity(SheetContentDiff sd, List<RawRow> aOnly, List<RawRow> bOnly,
                                          Map<Integer, Integer> updatedAtoB) {
        int n = aOnly.size();
        int m = bOnly.size();
        if (n == 0 && m == 0) return;
        if (n == 0) { for (RawRow r : bOnly) addRow(sd, r, ContentCompare.ADDED); return; }
        if (m == 0) { for (RawRow r : aOnly) addRow(sd, r, ContentCompare.DELETED); return; }

        // 预拆分单元格
        List<String[]> aCells = new ArrayList<>(n);
        for (RawRow r : aOnly) aCells.add(splitArr(r.content));
        List<String[]> bCells = new ArrayList<>(m);
        for (RawRow r : bOnly) bCells.add(splitArr(r.content));

        // 倒排索引: "列#值" -> bOnly 下标(跳过空值)
        Map<String, List<Integer>> index = new HashMap<>();
        for (int j = 0; j < m; j++) {
            String[] cells = bCells.get(j);
            for (int c = 0; c < cells.length; c++) {
                if (cells[c].isEmpty()) continue;
                index.computeIfAbsent(c + "\u0001" + cells[c], k -> new ArrayList<>()).add(j);
            }
        }

        // 为每个 aOnly 找最相似的 bOnly 候选
        List<double[]> proposals = new ArrayList<>(); // {score, i, j}
        for (int i = 0; i < n; i++) {
            String[] cells = aCells.get(i);
            Set<Integer> candidates = new HashSet<>();
            for (int c = 0; c < cells.length; c++) {
                if (cells[c].isEmpty()) continue;
                List<Integer> posting = index.get(c + "\u0001" + cells[c]);
                if (posting != null && posting.size() <= CANDIDATE_POSTING_CAP) candidates.addAll(posting);
            }
            int bestJ = -1;
            double best = 0;
            for (int j : candidates) {
                double s = similarity(aOnly.get(i).content, bOnly.get(j).content);
                if (s > best) { best = s; bestJ = j; }
            }
            if (bestJ >= 0 && best >= UPDATE_SIM_THRESHOLD) {
                proposals.add(new double[]{best, i, bestJ});
            }
        }

        // 按相似度降序贪心配对(每行只用一次)
        proposals.sort((x, y) -> Double.compare(y[0], x[0]));
        boolean[] aUsed = new boolean[n];
        boolean[] bUsed = new boolean[m];
        for (double[] p : proposals) {
            int i = (int) p[1];
            int j = (int) p[2];
            if (aUsed[i] || bUsed[j]) continue;
            aUsed[i] = true;
            bUsed[j] = true;
            sd.getRows().add(buildUpdated(bOnly.get(j).rowNum, aOnly.get(i).content, bOnly.get(j).content));
            updatedAtoB.put(aOnly.get(i).rowNum, bOnly.get(j).rowNum);
        }
        for (int i = 0; i < n; i++) if (!aUsed[i]) addRow(sd, aOnly.get(i), ContentCompare.DELETED);
        for (int j = 0; j < m; j++) if (!bUsed[j]) addRow(sd, bOnly.get(j), ContentCompare.ADDED);
    }

    /**
     * 将差异行统一到目标侧(B)坐标系后排序展示。
     * 新增/更新行本就带 B 行号;删除行在 B 中已不存在,若沿用 A 行号,在 B 有净增删时会与其它行的 B 行号错位
     * (表现为删除行整体偏移、滞后)。这里把删除行锚定到"它前面那个仍存在于 B 的行"的 B 行号之后,
     * 使其落在被删除的真实位置上,而展示的行号仍保留其在 A 中的原始行号。
     */
    private static void sortByBPosition(SheetContentDiff sd, SheetData a, SheetData b,
                                        List<RawRow> aOnly, List<RawRow> bOnly,
                                        Map<Integer, Integer> updatedAtoB) {
        // 删除行(A 行号):残差中未被匹配为"更新"的 A 行
        Set<Integer> deletedA = new HashSet<>();
        for (RawRow r : aOnly) {
            if (!updatedAtoB.containsKey(r.rowNum)) deletedA.add(r.rowNum);
        }
        // 未变更的 B 行:按内容建队列,供未变更 A 行在 A 顺序下依次取得其对应 B 行号(作为锚点)
        Set<Integer> bOnlyNums = new HashSet<>();
        for (RawRow r : bOnly) bOnlyNums.add(r.rowNum);
        Map<String, Deque<Integer>> bUnchanged = new HashMap<>();
        for (RawRow r : b.dataRows) {
            if (bOnlyNums.contains(r.rowNum)) continue;
            bUnchanged.computeIfAbsent(r.content, k -> new ArrayDeque<>()).add(r.rowNum);
        }
        // 按 A 顺序推进锚点,为每个删除行计算"B 坐标系"排序键
        Map<Integer, Double> delKey = new HashMap<>();
        int anchorB = 0;   // 最近一个仍存在于 B 的行对应的 B 行号(0 表示位于首个数据行之前)
        int gapSeq = 0;    // 同一锚点后的删除序号,保持多条连续删除按 A 顺序排列
        for (RawRow ar : a.dataRows) {
            Integer upB = updatedAtoB.get(ar.rowNum);
            if (upB != null) {                       // 更新行:锚点移动到其 B 行号
                anchorB = upB;
                gapSeq = 0;
            } else if (deletedA.contains(ar.rowNum)) { // 删除行:锚定到当前 anchorB 之后
                delKey.put(ar.rowNum, anchorB + (++gapSeq) * 1e-6);
            } else {                                  // 未变更行:用内容队列取得其 B 行号作为新锚点
                Deque<Integer> q = bUnchanged.get(ar.content);
                if (q != null && !q.isEmpty()) {
                    anchorB = q.poll();
                    gapSeq = 0;
                }
            }
        }
        // 删除行用锚定键;新增/更新行直接用其 B 行号
        sd.getRows().sort(Comparator.<RowDiff>comparingDouble(rd ->
                ContentCompare.DELETED.equals(rd.getStatus())
                        ? delKey.getOrDefault(rd.getRowNum(), (double) rd.getRowNum())
                        : (double) rd.getRowNum()));
    }

    private static String[] splitArr(String content) {
        return content.isEmpty() ? new String[0] : content.split(String.valueOf(CELL_SEP), -1);
    }

    private static void addRow(SheetContentDiff sd, RawRow r, String status) {
        sd.getRows().add(buildSingle(r.rowNum, status, splitCells(r.content), ContentCompare.DELETED.equals(status)));
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
            // 取单元格"显示值"作为内容:数字/小数按其呈现完整保留,不做四舍五入或去零
            DataFormatter formatter = new DataFormatter();
            // 公式取计算值而非公式文本(相对引用会随行号变动,否则插入行会让后续行全部"变化")
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

    /**
     * 取单元格内容(显示值):
     * - 公式 -> 计算值(而非公式文本);
     * - 数字/小数/日期 -> 按单元格呈现的完整值(不四舍五入、不丢小数);
     * 计算失败(如外部链接)时回退到不求值的格式化,再失败回退空串。
     */
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
