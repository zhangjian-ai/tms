-- 用例生成任务新增「图片解析」开关：为真时才解析文档内图片并回填原文，否则仅解析文本
ALTER TABLE `test_gen_task`
    ADD COLUMN `parse_image` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否解析文档内图片(0否1是)' AFTER `prd_type`;
