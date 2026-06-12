package com.seeker.tms.biz.confdiff.entities.compare;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 模块二:文件对比。以基准侧(A)为基准,分析目标侧(B)缺少/多出/内容不同的文件。
 */
@Data
public class FileCompare {

    /** 目标侧缺少的文件(基准侧有、目标侧无) */
    private List<FileEntry> missingInTarget = new ArrayList<>();
    /** 目标侧多出的文件(目标侧有、基准侧无) */
    private List<FileEntry> extraInTarget = new ArrayList<>();
    /** 两侧都有但内容不同的非Excel文件(Excel 的内容差异见文件内容对比模块) */
    private List<FileEntry> contentChanged = new ArrayList<>();

    public boolean isEmpty() {
        return missingInTarget.isEmpty() && extraInTarget.isEmpty() && contentChanged.isEmpty();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FileEntry {
        /** 文件相对路径 */
        private String path;
        /** 所在目录(根目录为空串) */
        private String dir;
        /** 基准侧 A 的 md5(仅内容不同的文件填充) */
        private String md5A;
        /** 目标侧 B 的 md5(仅内容不同的文件填充) */
        private String md5B;

        public FileEntry(String path, String dir) {
            this.path = path;
            this.dir = dir;
        }
    }
}
