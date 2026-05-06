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
    private String type;  // root, module, point, case, step, free
    private List<String> icons;  // priority-1, priority-2, etc.
    private Boolean expanded;
    private List<XMindNode> children;
}
