package com.seeker.tms.biz.testgen.agent;

import com.alibaba.fastjson.JSON;
import com.seeker.tms.biz.testgen.entities.XMindNode;
import dev.langchain4j.agent.tool.Tool;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
public class XMindTreeTools {

    @Getter
    private final XMindNode root;
    @Getter
    private final List<String> changedNodeIds = new ArrayList<>();

    public XMindTreeTools(XMindNode root) {
        this.root = root;
    }

    @Tool("修改指定节点的标题。nodeId: 节点ID, newTitle: 新标题")
    public String modifyNode(String nodeId, String newTitle) {
        XMindNode node = findNode(root, nodeId);
        if (node == null) return "节点不存在: " + nodeId;
        node.setTitle(newTitle);
        changedNodeIds.add(nodeId);
        return "已修改节点标题为: " + newTitle;
    }

    @Tool("在指定父节点下添加子节点。parentId: 父节点ID, title: 标题, type: 节点类型(module/point/case/step)")
    public String addNode(String parentId, String title, String type) {
        XMindNode parent = findNode(root, parentId);
        if (parent == null) return "父节点不存在: " + parentId;
        XMindNode child = createNode(type + "_" + UUID.randomUUID(), title, type);
        parent.getChildren().add(child);
        changedNodeIds.add(child.getId());
        return "已在 [" + parent.getTitle() + "] 下添加节点: " + title;
    }

    @Tool("删除指定节点及其所有子节点。nodeId: 要删除的节点ID")
    public String deleteNode(String nodeId) {
        if ("root".equals(nodeId)) return "不能删除根节点";
        String title = findNodeTitle(root, nodeId);
        boolean removed = removeNode(root, nodeId);
        if (!removed) return "节点不存在: " + nodeId;
        changedNodeIds.add(nodeId);
        return "已删除节点: " + (title != null ? title : nodeId);
    }

    @Tool("移动节点到新的父节点下。nodeId: 要移动的节点ID, newParentId: 新父节点ID")
    public String moveNode(String nodeId, String newParentId) {
        XMindNode node = findNode(root, nodeId);
        XMindNode newParent = findNode(root, newParentId);
        if (node == null) return "节点不存在: " + nodeId;
        if (newParent == null) return "目标父节点不存在: " + newParentId;
        removeNode(root, nodeId);
        newParent.getChildren().add(node);
        changedNodeIds.add(nodeId);
        return "已将 [" + node.getTitle() + "] 移动到 [" + newParent.getTitle() + "] 下";
    }

    @Tool("在指定模块下批量添加测试点。parentModuleId: 父模块节点ID, pointContents: 测试点内容列表，用JSON数组字符串表示，如[\"测试点1\",\"测试点2\"]")
    public String addTestPoints(String parentModuleId, String pointContents) {
        XMindNode parent = findNode(root, parentModuleId);
        if (parent == null) return "模块不存在: " + parentModuleId;
        List<String> contents = JSON.parseArray(pointContents, String.class);
        for (String content : contents) {
            XMindNode point = createNode("point_" + UUID.randomUUID(), content, "point");
            parent.getChildren().add(point);
            changedNodeIds.add(point.getId());
        }
        return "已在 [" + parent.getTitle() + "] 下添加 " + contents.size() + " 个测试点";
    }

    @Tool("添加一个新的功能模块节点。parentId: 父节点ID(通常是root), moduleName: 模块名称")
    public String addModule(String parentId, String moduleName) {
        XMindNode parent = findNode(root, parentId);
        if (parent == null) return "父节点不存在: " + parentId;
        XMindNode module = createNode("module_" + UUID.randomUUID(), moduleName, "module");
        parent.getChildren().add(module);
        changedNodeIds.add(module.getId());
        return "已添加功能模块: " + moduleName;
    }

    // ---- 辅助方法 ----

    private XMindNode createNode(String id, String title, String type) {
        XMindNode node = new XMindNode();
        node.setId(id);
        node.setTitle(title);
        node.setType(type);
        node.setExpanded(true);
        node.setChildren(new ArrayList<>());
        return node;
    }

    private XMindNode findNode(XMindNode node, String id) {
        if (node == null) return null;
        if (id.equals(node.getId())) return node;
        if (node.getChildren() != null) {
            for (XMindNode child : node.getChildren()) {
                XMindNode found = findNode(child, id);
                if (found != null) return found;
            }
        }
        return null;
    }

    private String findNodeTitle(XMindNode node, String id) {
        XMindNode found = findNode(node, id);
        return found != null ? found.getTitle() : null;
    }

    private boolean removeNode(XMindNode parent, String id) {
        if (parent.getChildren() == null) return false;
        for (int i = 0; i < parent.getChildren().size(); i++) {
            if (id.equals(parent.getChildren().get(i).getId())) {
                parent.getChildren().remove(i);
                return true;
            }
            if (removeNode(parent.getChildren().get(i), id)) return true;
        }
        return false;
    }
}
