package com.seeker.tms.biz.confdiff.entities.compare;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 模块三:文件内容对比(Excel 数据行,细化到单元格)。
 * 以基准侧(A)为基准,目标侧(B)相对 A 的行变化:新增/删除/更新;更新呈现到单元格 'A' -> 'B'。
 */
@Data
public class ContentCompare {

    /** 存在内容差异的文件列表 */
    private List<FileContentDiff> files = new ArrayList<>();

    public boolean isEmpty() {
        return files.isEmpty();
    }

    /** 单个文件的内容差异 */
    @Data
    public static class FileContentDiff {
        /** 文件相对路径 */
        private String path;
        /** 解析失败信息(可空) */
        private String error;
        /** 目标侧缺少的 sheet(基准侧有、目标侧无) */
        private List<String> sheetsMissingInTarget = new ArrayList<>();
        /** 目标侧多出的 sheet(目标侧有、基准侧无) */
        private List<String> sheetsExtraInTarget = new ArrayList<>();
        /** 两侧共有 sheet 的行差异 */
        private List<SheetContentDiff> sheets = new ArrayList<>();

        public FileContentDiff(String path) {
            this.path = path;
        }

        public boolean isEmpty() {
            return error == null && sheetsMissingInTarget.isEmpty() && sheetsExtraInTarget.isEmpty()
                    && sheets.stream().allMatch(SheetContentDiff::isEmpty);
        }
    }

    /** 单个 sheet 的行差异(合并为一张表) */
    @Data
    public static class SheetContentDiff {
        /** 所属文件相对路径 */
        private String file;
        /** sheet 名 */
        private String sheet;
        /** 表头(该 sheet 第一行,优先取目标侧) */
        private List<String> header = new ArrayList<>();
        /** 差异行(新增/删除/更新),按行号升序 */
        private List<RowDiff> rows = new ArrayList<>();

        public SheetContentDiff(String file, String sheet) {
            this.file = file;
            this.sheet = sheet;
        }

        public boolean isEmpty() {
            return rows.isEmpty();
        }
    }

    /** 行级状态 */
    public static final String ADDED = "ADDED";     // 目标侧新增
    public static final String DELETED = "DELETED"; // 目标侧删除
    public static final String UPDATED = "UPDATED"; // 行内单元格更新

    /** 一行差异 */
    @Data
    public static class RowDiff {
        /** 真实 Excel 行号 */
        private int rowNum;
        /** ADDED / DELETED / UPDATED */
        private String status;
        /** 各单元格 */
        private List<CellDiff> cells = new ArrayList<>();

        public RowDiff(int rowNum, String status) {
            this.rowNum = rowNum;
            this.status = status;
        }
    }

    /** 一个单元格的差异:基准侧值 a、目标侧值 b、是否变化 */
    @Data
    public static class CellDiff {
        private String a;
        private String b;
        private boolean changed;

        public CellDiff(String a, String b, boolean changed) {
            this.a = a;
            this.b = b;
            this.changed = changed;
        }
    }
}
