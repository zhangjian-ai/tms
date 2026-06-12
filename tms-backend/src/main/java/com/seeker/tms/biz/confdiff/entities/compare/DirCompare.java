package com.seeker.tms.biz.confdiff.entities.compare;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 模块一:目录对比。以基准侧(A)为基准,分析目标侧(B)缺少/多出的目录。
 */
@Data
public class DirCompare {

    /** 目标侧缺少的目录(基准侧有、目标侧无) */
    private List<DirEntry> missingInTarget = new ArrayList<>();
    /** 目标侧多出的目录(目标侧有、基准侧无) */
    private List<DirEntry> extraInTarget = new ArrayList<>();

    public boolean isEmpty() {
        return missingInTarget.isEmpty() && extraInTarget.isEmpty();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DirEntry {
        /** 目录相对路径 */
        private String path;
        /** 父目录(根目录为空串) */
        private String parent;
    }
}
