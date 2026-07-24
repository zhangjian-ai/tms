package com.seeker.tms.biz.testgen.entities;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class XMindNode {
    private String id;
    private String title;
    private String type;  // root, module, case, step, free
    private List<String> icons;  // priority-1, priority-2, etc.
    private Boolean expanded;
    private List<XMindNode> children;
    private String chapterId;  // 章节模块节点专用：关联大纲章节的内部ID，仅逻辑层用于章节内精修分组，不下发大模型
}
