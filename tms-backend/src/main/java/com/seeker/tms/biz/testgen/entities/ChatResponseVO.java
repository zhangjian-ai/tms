package com.seeker.tms.biz.testgen.entities;

import lombok.Data;

import java.util.List;

@Data
public class ChatResponseVO {
    private String message;
    private XMindNode treeData;
    private List<String> changedNodeIds;
}
