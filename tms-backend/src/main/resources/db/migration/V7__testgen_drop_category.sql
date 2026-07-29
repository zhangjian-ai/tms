-- 用例生成优化：去掉「分类(category)」概念，目录层级统一取自章节名（章节名用中划线"-"拆分为多级目录）。
-- 涉及提示词契约变更（去掉 category 字段/枚举/相关规则；planning 允许章节名用"-"表达多级目录）：
-- 清除这些 DB 已绑定的官方提示词记录，应用启动时按新的 classpath 文件重新导入。
-- 注意：这会覆盖对这些阶段的自定义提示词（旧提示词与新结构不兼容）。

-- 契约变更：清除旧绑定，启动时重新导入新版官方提示词
DELETE FROM `testgen_prompt`
    WHERE `stage_key` IN ('planning_system', 'case_gen_system', 'case_gen_manual_system',
                          'refine_chapter_system', 'refine_global_system');

-- 阶段说明更新（不再有分类，目录层级来自章节名的"-"拆分）
UPDATE `testgen_stage` SET `description` = '规划测试大纲；章节名可用中划线"-"表达多级目录层级'
    WHERE `stage_key` = 'planning_system';
UPDATE `testgen_stage` SET `description` = '基于章节大纲直接生成测试用例（不预设分类）'
    WHERE `stage_key` = 'case_gen_system';
UPDATE `testgen_stage` SET `description` = '基于需求文档+当前章节+用户补充测试内容，为目录节点直接生成用例'
    WHERE `stage_key` = 'case_gen_manual_system';
