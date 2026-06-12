package com.seeker.tms.biz.confdiff.support;

import cn.hutool.core.io.FileUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.seeker.tms.biz.confdiff.entities.compare.DirCompare;
import com.seeker.tms.biz.confdiff.entities.compare.FileCompare;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 目录结构 / 文件清单对比(纯本地、静态)。
 * 以基准侧(rootA)为基准,分析目标侧(rootB)缺少/多出的目录与文件。
 */
public class DirFileComparator {

    private DirFileComparator() {}

    /** 模块一:目录对比 */
    public static DirCompare diffDirs(File rootA, File rootB) {
        Set<String> a = relativeDirs(rootA);
        Set<String> b = relativeDirs(rootB);
        DirCompare cmp = new DirCompare();
        for (String d : a) {
            if (!b.contains(d)) cmp.getMissingInTarget().add(new DirCompare.DirEntry(d, parentOf(d)));
        }
        for (String d : b) {
            if (!a.contains(d)) cmp.getExtraInTarget().add(new DirCompare.DirEntry(d, parentOf(d)));
        }
        return cmp;
    }

    /** 模块二:文件对比(含两侧共有但内容不同的非 Excel 文件) */
    public static FileCompare diffFiles(File rootA, File rootB) {
        Set<String> a = relativeFiles(rootA);
        Set<String> b = relativeFiles(rootB);
        FileCompare cmp = new FileCompare();
        for (String f : a) {
            if (!b.contains(f)) cmp.getMissingInTarget().add(new FileCompare.FileEntry(f, dirOf(f)));
        }
        for (String f : b) {
            if (!a.contains(f)) cmp.getExtraInTarget().add(new FileCompare.FileEntry(f, dirOf(f)));
        }
        // 共有文件:Excel 走行级对比;其余(含 .bytes 等二进制)按 md5 比对内容,不一致则记录两侧 md5
        for (String rel : a) {
            if (!b.contains(rel) || isExcel(rel)) continue;
            String md5A = md5(new File(rootA, rel));
            String md5B = md5(new File(rootB, rel));
            if (!md5A.equals(md5B)) {
                cmp.getContentChanged().add(new FileCompare.FileEntry(rel, dirOf(rel), md5A, md5B));
            }
        }
        return cmp;
    }

    /** 两侧共有的 excel 文件相对路径 */
    public static List<String> commonExcelFiles(File rootA, File rootB) {
        Set<String> a = relativeFiles(rootA);
        Set<String> b = relativeFiles(rootB);
        List<String> result = new ArrayList<>();
        for (String rel : a) {
            if (b.contains(rel) && isExcel(rel)) result.add(rel);
        }
        return result;
    }

    public static boolean isExcel(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".xls") || lower.endsWith(".xlsx");
    }

    /** 父目录;根目录返回空串 */
    private static String parentOf(String rel) {
        int idx = rel.lastIndexOf('/');
        return idx < 0 ? "" : rel.substring(0, idx);
    }

    /** 文件所在目录;根目录返回空串 */
    private static String dirOf(String rel) {
        return parentOf(rel);
    }

    private static Set<String> relativeDirs(File root) {
        Set<String> set = new TreeSet<>();
        if (!root.exists()) return set;
        Path base = root.toPath();
        for (File f : FileUtil.loopFiles(root, f -> true)) {
            File parent = f.getParentFile();
            while (parent != null && !parent.toPath().equals(base) && parent.toPath().startsWith(base)) {
                set.add(base.relativize(parent.toPath()).toString().replace('\\', '/'));
                parent = parent.getParentFile();
            }
        }
        return set;
    }

    private static Set<String> relativeFiles(File root) {
        Set<String> set = new TreeSet<>();
        if (!root.exists()) return set;
        Path base = root.toPath();
        for (File f : FileUtil.loopFiles(root, f -> true)) {
            set.add(base.relativize(f.toPath()).toString().replace('\\', '/'));
        }
        return set;
    }

    private static String md5(File f) {
        return DigestUtil.md5Hex(f);
    }
}
