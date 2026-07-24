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
 * 以及 root → 分类(module) → 章节(module) → 用例(case) → 步骤(step) 的挂树逻辑。
 * 所有方法只操作传入的 {@link XMindNode}，不持有任何状态。
 */
@Slf4j
public final class XMindTrees {

    private XMindTrees() {}

    /** 用例分类（顶层目录，顺序固定）。树层级：root → 分类(module) → 章节(module) → 用例(case) → 步骤(step) */
    public static final List<String> CATEGORY_ORDER =
            List.of("功能逻辑", "美术效果", "配置管理", "数据埋点", "异常场景");
    public static final String DEFAULT_CATEGORY = "功能逻辑";

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

    /** 统计带 failed 标记的章节模块数（章节挂在分类目录下，递归统计整棵树） */
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
     * 过滤树用于 XMind 导出：树已是 根 → 分类 → 章节 → 用例 → 步骤，只需剔除 free 自由节点。
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
     * 自动生成/补漏用例的挂树：root → 分类(module) → 章节(module) → 用例(case)。
     * 分类节点已预建（按 title 匹配、chapterId=null），章节节点按 chapterId 找/建。返回用例所在分类节点。
     */
    public static XMindNode placeCaseByCategoryChapter(XMindNode root, String chapterName, String chapterId,
                                                       String category, XMindNode caseNode) {
        String cat = (category == null || category.isBlank()) ? DEFAULT_CATEGORY : tidyTitle(category);
        XMindNode categoryNode = findOrCreateCategory(root, cat);
        XMindNode chapterNode = findOrCreateChapter(categoryNode, chapterName, chapterId);
        chapterNode.getChildren().add(caseNode);
        return categoryNode;
    }

    /** 在 root 下按标题找分类目录（chapterId=null 的直接子模块），没有则新建 */
    public static XMindNode findOrCreateCategory(XMindNode root, String cat) {
        if (root.getChildren() == null) root.setChildren(new ArrayList<>());
        for (XMindNode c : root.getChildren()) {
            if ("module".equals(c.getType()) && c.getChapterId() == null && cat.equals(c.getTitle())) {
                return c;
            }
        }
        XMindNode n = newNode("module_" + UUID.randomUUID(), cat, "module");
        root.getChildren().add(n);
        return n;
    }

    /** 在分类目录下按 chapterId（回退章节名）找章节模块，没有则新建（带 chapterId） */
    public static XMindNode findOrCreateChapter(XMindNode categoryNode, String chapterName, String chapterId) {
        if (categoryNode.getChildren() == null) categoryNode.setChildren(new ArrayList<>());
        for (XMindNode c : categoryNode.getChildren()) {
            if (!"module".equals(c.getType())) continue;
            boolean match = (chapterId != null && chapterId.equals(c.getChapterId()))
                    || (chapterId == null && chapterName != null && chapterName.equals(c.getTitle()));
            if (match) return c;
        }
        XMindNode n = newNode("module_" + UUID.randomUUID(), chapterName, "module");
        n.setChapterId(chapterId);
        categoryNode.getChildren().add(n);
        return n;
    }

    /**
     * 把用例移动到目标分类目录（保持所属章节不变）：root → 目标分类 → 同章节 → 用例。
     * 仅在树为 分类→章节→用例 结构、且目标分类与当前分类不同时移动；否则原地不动。
     */
    public static void moveCaseToCategory(XMindNode root, XMindNode caseNode, String newCategory) {
        if (root == null || caseNode == null || newCategory == null || newCategory.isBlank()) return;
        String target = tidyTitle(newCategory);
        XMindNode chapterNode = findParent(root, caseNode.getId());        // 用例所在章节节点
        if (chapterNode == null) return;
        XMindNode categoryNode = findParent(root, chapterNode.getId());    // 章节所在分类节点
        if (categoryNode == null || "root".equals(categoryNode.getType())) return; // 非标准结构，跳过
        if (target.equals(tidyTitle(categoryNode.getTitle()))) return;     // 分类未变化
        // 从当前章节摘除，按 目标分类 → 同章节（chapterId/名一致）重新挂
        if (chapterNode.getChildren() != null) chapterNode.getChildren().remove(caseNode);
        placeCaseByCategoryChapter(root, tidyTitle(chapterNode.getTitle()), chapterNode.getChapterId(), target, caseNode);
    }
}
