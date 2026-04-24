package com.seeker.tms.biz.testgen.entities;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class RestoreVO {
    private TaskVO task;
    private XMindNode treeData;
    private List<Map<String, String>> chatHistory;
    private List<String> generatingPointIds;
}
