package com.seeker.tms.biz.testgen.utils;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.seeker.tms.biz.testgen.entities.XMindNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * XMind 树的无状态纯函数工具集：节点构建/查找/删除、类型统计、结构清理、导出过滤，
 * 以及 root → 模块(module，可多级) → 用例(case) → 步骤(step) 的挂树逻辑。
 * 目录层级直接取自章节名：章节名用中划线 "-" 分隔即拆成多级模块目录。
 * 所有方法只操作传入的 {@link XMindNode}，不持有任何状态。
 */
@Slf4j
public final class XMindTrees {

    private XMindTrees() {}

    public static XMindNode newNode(String id, String title, String type) {
        XMindNode n = new XMindNode();
        n.setId(id);
        n.setTitle(title);
        n.setType(type);
        n.setExpanded(true);
        n.setChildren(new ArrayList<>());
        return n;
    }

    public static XMindNode findNodeById(XMindNode node, String id) {
        if (node == null) return null;
        if (id.equals(node.getId())) return node;
        if (node.getChildren() != null) {
            for (XMindNode child : node.getChildren()) {
                XMindNode found = findNodeById(child, id);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** 查找 childId 节点的父节点，未找到返回 null */
    public static XMindNode findParent(XMindNode node, String childId) {
        if (node == null || node.getChildren() == null) return null;
        for (XMindNode child : node.getChildren()) {
            if (childId.equals(child.getId())) return node;
            XMindNode found = findParent(child, childId);
            if (found != null) return found;
        }
        return null;
    }

    /** 递归在树中按 id 删除节点；删除成功返回 true */
    public static boolean removeNodeById(XMindNode node, String targetId) {
        if (node == null || node.getChildren() == null) return false;
        Iterator<XMindNode> it = node.getChildren().iterator();
        while (it.hasNext()) {
            XMindNode child = it.next();
            if (targetId.equals(child.getId())) {
                it.remove();
                return true;
            }
            if (removeNodeById(child, targetId)) return true;
        }
        return false;
    }

    public static int countNodes(XMindNode node, String type) {
        int count = type.equals(node.getType()) ? 1 : 0;
        if (node.getChildren() != null) {
            for (XMindNode child : node.getChildren()) {
                count += countNodes(child, type);
            }
        }
        return count;
    }

    /** 统计带 failed 标记的模块数（失败章节标记在其叶子模块上，递归统计整棵树） */
    public static int countFailedChapters(XMindNode node) {
        if (node == null) return 0;
        int count = 0;
        if ("module".equals(node.getType()) && node.getIcons() != null
                && node.getIcons().contains("failed")) {
            count++;
        }
        if (node.getChildren() != null) {
            for (XMindNode child : node.getChildren()) {
                count += countFailedChapters(child);
            }
        }
        return count;
    }

    /** 自底向上剪除没有任何用例的空目录（module 节点），返回被剪除的目录数 */
    public static int pruneEmptyModules(XMindNode node) {
        if (node == null || node.getChildren() == null || node.getChildren().isEmpty()) return 0;
        int removed = 0;
        for (XMindNode child : node.getChildren()) {
            removed += pruneEmptyModules(child);
        }
        List<XMindNode> kept = new ArrayList<>(node.getChildren().size());
        for (XMindNode child : node.getChildren()) {
            if ("module".equals(child.getType()) && !hasMeaningfulContent(child)) {
                removed++;
            } else {
                kept.add(child);
            }
        }
        node.setChildren(kept);
        return removed;
    }

    /** 子树中是否有意义：含用例/自由节点，或被标记 failed（保留失败章节供重试） */
    public static boolean hasMeaningfulContent(XMindNode node) {
        if (node == null) return false;
        String type = node.getType();
        if ("case".equals(type) || "free".equals(type)) return true;
        if (node.getIcons() != null && node.getIcons().contains("failed")) return true;
        if (node.getChildren() != null) {
            for (XMindNode child : node.getChildren()) {
                if (hasMeaningfulContent(child)) return true;
            }
        }
        return false;
    }

    /** 收集所有 case 节点 id（case 下不再递归，step 不参与去重） */
    public static void collectCaseIds(XMindNode node, List<String> out) {
        if (node == null) return;
        if ("case".equals(node.getType())) {
            out.add(node.getId());
            return;
        }
        if (node.getChildren() != null) {
            for (XMindNode child : node.getChildren()) collectCaseIds(child, out);
        }
    }

    /** 构建单个用例节点 */
    public static XMindNode buildSingleCaseNode(JSONObject c) {
        String caseName = c.getString("用例名称");
        if (caseName == null) return null;

        String priority = c.getString("优先级");
        List<String> icons = null;
        if (priority != null && priority.startsWith("P")) {
            try {
                int level = Integer.parseInt(priority.substring(1));
                icons = List.of("priority-" + (level + 1));
            } catch (NumberFormatException e) {
                log.warn("无法解析优先级: {}", priority);
            }
        }
        XMindNode caseNode = newNode("case_" + UUID.randomUUID(), caseName, "case");
        caseNode.setIcons(icons);

        List<XMindNode> children = new ArrayList<>();
        String pre = c.getString("前置条件");
        if (pre != null && !pre.isBlank()) {
            children.add(newNode("step_" + UUID.randomUUID(), "前置条件:\n" + pre, "step"));
        } else {
            // 保证必有一个前置条件
            children.add(newNode("step_" + UUID.randomUUID(), "前置条件:", "step"));
        }
        JSONArray steps = c.getJSONArray("测试步骤");
        if (steps != null) {
            for (int j = 0; j < steps.size(); j++) {
                JSONObject step = steps.getJSONObject(j);
                XMindNode stepNode = newNode("step_" + UUID.randomUUID(), step.getString("执行操作"), "step");
                String expected = step.getString("预期结果");
                if (expected != null && !expected.isBlank()) {
                    stepNode.setChildren(List.of(newNode("step_" + UUID.randomUUID(), expected, "step")));
                }
                children.add(stepNode);
            }
        }
        caseNode.setChildren(children);
        return caseNode;
    }

    /**
     * 把 case 节点还原成结构化字段（buildSingleCaseNode 的逆操作）：
     * {用例名称, 优先级, 前置条件, 测试步骤:[{执行操作,预期结果}]}
     */
    public static Map<String, Object> caseNodeToJson(XMindNode caseNode) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("用例名称", caseNode.getTitle());
        // 优先级：icons 里的 priority-N 还原为 P(N-1)
        String priority = "";
        if (caseNode.getIcons() != null) {
            for (String icon : caseNode.getIcons()) {
                if (icon != null && icon.startsWith("priority-")) {
                    try {
                        int n = Integer.parseInt(icon.substring("priority-".length()));
                        priority = "P" + (n - 1);
                    } catch (NumberFormatException ignore) { }
                    break;
                }
            }
        }
        m.put("优先级", priority);
        String pre = "";
        List<Map<String, String>> steps = new ArrayList<>();
        if (caseNode.getChildren() != null) {
            for (XMindNode child : caseNode.getChildren()) {
                String t = child.getTitle() == null ? "" : child.getTitle();
                if (t.startsWith("前置条件:")) {
                    pre = t.substring("前置条件:".length()).replaceFirst("^\\n", "").trim();
                } else {
                    Map<String, String> step = new LinkedHashMap<>();
                    step.put("执行操作", t);
                    String expected = (child.getChildren() != null && !child.getChildren().isEmpty())
                            ? child.getChildren().get(0).getTitle() : "";
                    step.put("预期结果", expected == null ? "" : expected);
                    steps.add(step);
                }
            }
        }
        m.put("前置条件", pre);
        m.put("测试步骤", steps);
        return m;
    }

    public static String tidyTitle(String s) {
        if (s == null) return "";
        String t = s.replaceAll("[\\r\\n]+", " ").replaceAll("\\s+", " ").trim();
        t = t.replaceAll("[\\-—:：、。,，；;.]+$", "");
        return t.trim();
    }

    /**
     * 过滤树用于 XMind 导出：树已是 根 → 模块(可多级) → 用例 → 步骤，只需剔除 free 自由节点。
     */
    public static XMindNode filterForExport(XMindNode node) {
        if ("free".equals(node.getType())) {
            return null;
        }

        XMindNode filtered = new XMindNode();
        filtered.setId(node.getId());
        filtered.setTitle(node.getTitle());
        filtered.setType(node.getType());
        filtered.setIcons(node.getIcons());
        filtered.setExpanded(node.getExpanded());

        if (node.getChildren() != null) {
            List<XMindNode> filteredChildren = new ArrayList<>();
            for (XMindNode child : node.getChildren()) {
                XMindNode filteredChild = filterForExport(child);
                if (filteredChild != null) {
                    filteredChildren.add(filteredChild);
                }
            }
            filtered.setChildren(filteredChildren);
        }

        return filtered;
    }

    /**
     * 把章节名按中划线 "-" 拆成多级模块路径（逐段 tidy、丢弃空段）。
     * 无有效分段时回退为整条 tidy 后的名称，仍为空则用「未命名」兜底。
     */
    public static List<String> splitChapterPath(String chapterName) {
        List<String> segments = new ArrayList<>();
        if (chapterName != null) {
            for (String seg : chapterName.split("-")) {
                String s = tidyTitle(seg);
                if (!s.isEmpty()) segments.add(s);
            }
        }
        if (segments.isEmpty()) {
            String whole = tidyTitle(chapterName);
            segments.add(whole.isEmpty() ? "未命名" : whole);
        }
        return segments;
    }

    /**
     * 按章节路径（"-" 分隔的多级目录）在 root 下找/建嵌套模块，返回最末（叶子）模块。
     * 中间目录按标题匹配复用（作为容器，不看 chapterId）；
     * 叶子模块按「标题 + chapterId」精确匹配，彻底区分同名同路径但 chapterId 不同的章节——
     * 找不到精确匹配时，采纳同名且尚未标记 chapterId 的既有目录并补标，否则新建叶子。
     */
    public static XMindNode findOrCreateChapterPath(XMindNode root, String chapterName, String chapterId) {
        if (root.getChildren() == null) root.setChildren(new ArrayList<>());
        List<String> segments = splitChapterPath(chapterName);
        XMindNode parent = root;
        for (int i = 0; i < segments.size(); i++) {
            String seg = segments.get(i);
            boolean isLeaf = i == segments.size() - 1;
            if (parent.getChildren() == null) parent.setChildren(new ArrayList<>());
            XMindNode found = null;
            if (isLeaf && chapterId != null) {
                // 叶子：优先「标题 + chapterId」精确匹配；否则采纳同名且无 chapterId 的目录并补标
                XMindNode adoptable = null;
                for (XMindNode c : parent.getChildren()) {
                    if (!"module".equals(c.getType()) || !seg.equals(c.getTitle())) continue;
                    if (chapterId.equals(c.getChapterId())) { found = c; break; }
                    if (c.getChapterId() == null && adoptable == null) adoptable = c;
                }
                if (found == null && adoptable != null) {
                    found = adoptable;
                    found.setChapterId(chapterId);
                }
            } else {
                // 中间目录（或无 chapterId 的兜底）：按标题匹配复用
                for (XMindNode c : parent.getChildren()) {
                    if ("module".equals(c.getType()) && seg.equals(c.getTitle())) { found = c; break; }
                }
            }
            if (found == null) {
                found = newNode("module_" + UUID.randomUUID(), seg, "module");
                if (isLeaf && chapterId != null) found.setChapterId(chapterId);
                parent.getChildren().add(found);
            }
            parent = found;
        }
        return parent;
    }

    /**
     * 生成/补漏用例的挂树：按章节路径找/建叶子模块，把用例挂到叶子模块下，返回叶子模块（供增量推送）。
     */
    public static XMindNode placeCaseByChapterPath(XMindNode root, String chapterName,
                                                   String chapterId, XMindNode caseNode) {
        XMindNode leaf = findOrCreateChapterPath(root, chapterName, chapterId);
        if (leaf.getChildren() == null) leaf.setChildren(new ArrayList<>());
        leaf.getChildren().add(caseNode);
        return leaf;
    }
}
