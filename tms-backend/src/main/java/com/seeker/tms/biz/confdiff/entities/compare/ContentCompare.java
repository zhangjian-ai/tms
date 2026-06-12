package com.seeker.tms.biz.confdiff.entities.compare;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 模块三:文件内容对比(Excel 数据行)。以基准侧(A)为基准,
 * 分析目标侧(B)在各文件、各 sheet 上缺少/多出的数据行。
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
            return sheetsMissingInTarget.isEmpty() && sheetsExtraInTarget.isEmpty()
                    && sheets.stream().allMatch(SheetContentDiff::isEmpty);
        }
    }

    /** 单个 sheet 的行差异 */
    @Data
    public static class SheetContentDiff {
        /** 所属文件相对路径 */
        private String file;
        /** sheet 名 */
        private String sheet;
        /** 基准侧 A 的表头(该 sheet 第一行) */
        private List<String> headerA = new ArrayList<>();
        /** 目标侧 B 的表头(该 sheet 第一行) */
        private List<String> headerB = new ArrayList<>();
        /** 目标侧缺少的数据行(基准侧有、目标侧无),行号为基准侧 A 的真实 Excel 行号 */
        private List<RowLine> rowsMissingInTarget = new ArrayList<>();
        /** 目标侧多出的数据行(目标侧有、基准侧无),行号为目标侧 B 的真实 Excel 行号 */
        private List<RowLine> rowsExtraInTarget = new ArrayList<>();

        public SheetContentDiff(String file, String sheet) {
            this.file = file;
            this.sheet = sheet;
        }

        public boolean isEmpty() {
            return rowsMissingInTarget.isEmpty() && rowsExtraInTarget.isEmpty();
        }
    }

    /** 一条数据行:真实 Excel 行号 + 整行内容(单元格以 CELL_SEP 分隔) */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class RowLine {
        /** 真实 Excel 行号(1 起) */
        private int rowNum;
        /** 整行内容(单元格以 \u0001 分隔) */
        private String content;
    }
}
