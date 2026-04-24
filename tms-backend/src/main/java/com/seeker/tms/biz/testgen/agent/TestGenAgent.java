package com.seeker.tms.biz.testgen.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface TestGenAgent {

    @SystemMessage("""
            你是一个测试用例设计助手。用户正在编辑一棵测试用例树。
            树的节点类型有：root(根节点)、module(功能模块)、point(测试点)、case(测试用例)、step(测试步骤)。
            每个节点有 id、title、type、marker(优先级标记如priority-1)、children 字段。

            用户可能会要求你：
            - 修改某个测试点的内容
            - 添加新的测试点或模块
            - 删除不需要的节点
            - 调整节点的层级结构
            - 补充遗漏的测试场景

            请使用提供的工具函数来操作树结构。每次操作后用简洁的自然语言告诉用户你做了什么。
            """)
    String chat(@UserMessage String userMessage);
}
