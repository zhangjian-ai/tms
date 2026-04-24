# Prompt 模板文件说明

本目录存放所有 LLM 调用的 Prompt 模板，便于统一管理和维护。

## 文件列表

### 1. point_extract_system.txt
**用途**: 测试点提取的系统 Prompt  
**调用位置**: `TestGenServiceImpl.buildPointExtractSystem()`  
**说明**: 指导 LLM 从需求文档中提取测试点，定义输出格式和规则

### 2. case_gen_system.txt
**用途**: 测试用例生成的系统 Prompt  
**调用位置**: `TestGenServiceImpl.buildCaseGenSystem()`  
**说明**: 指导 LLM 根据测试点生成详细的测试用例，包含步骤和预期结果

### 3. image_recognize.txt
**用途**: 图片识别的 Prompt  
**调用位置**: `DocumentParserServiceImpl.recognizeImage()`  
**说明**: 指导视觉模型识别需求文档中的图片内容，支持上下文参数

**参数**:
- `{{context}}`: 图片周围的文本上下文（可选）

### 4. chat_summary.txt
**用途**: 对话历史摘要的 Prompt  
**调用位置**: `AgentChatServiceImpl.compactOldRounds()`  
**说明**: 将旧的对话历史压缩为简洁摘要，节省上下文空间

**参数**:
- `{{history}}`: 需要压缩的对话历史文本

## 使用方式

### 加载简单 Prompt
```java
String prompt = PromptLoader.load("point_extract_system");
```

### 加载带参数的 Prompt
```java
Map<String, String> params = Map.of("context", "图片上下文内容");
String prompt = PromptLoader.loadWithParams("image_recognize", params);
```

### 条件块语法
在 Prompt 中可以使用条件块，当参数为空时自动隐藏该块：
```
{{#context}}
该图片在文档中的上下文如下：
{{context}}
{{/context}}
```

## 修改建议

- 修改 Prompt 后无需重启服务，PromptLoader 会自动缓存
- 如需清除缓存，重启应用即可
- 建议在修改前备份原文件
- 保持 JSON 格式要求的一致性，避免解析失败
