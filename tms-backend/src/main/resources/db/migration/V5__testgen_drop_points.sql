-- 用例生成重构：去掉「测试点」概念。
-- 1) 删除测试点提取阶段及其绑定提示词；
-- 2) 精修/用例生成阶段的显示名与说明由「测试点」改为「用例」；
-- 3) case_gen / refine_chapter / refine_global 的提示词契约（输入/输出结构）已变更，
--    删除其已绑定的 testgen_prompt 记录，让应用启动时按新的 classpath 文件重新导入官方提示词。
--    注意：这会覆盖对这些阶段的自定义提示词（旧提示词与新结构不兼容）。

-- 删除测试点提取阶段
DELETE FROM `testgen_prompt` WHERE `stage_key` = 'extract_points_system';
DELETE FROM `testgen_stage`  WHERE `stage_key` = 'extract_points_system';

-- 新增「目录补充生成」阶段（case_gen_manual_user 为用户模板，不需 stage 行）
INSERT INTO `testgen_stage` (`stage_key`, `stage_name`, `description`, `sort_no`) VALUES
    ('case_gen_manual_system', '手动生成用例', '基于需求文档+用户补充信息，为目录节点直接生成用例', 9);

-- 阶段显示名/说明更新（测试点 -> 用例）
UPDATE `testgen_stage` SET `stage_name` = '用例精修-章节内', `description` = '按章节对用例去重·合并·补漏'
    WHERE `stage_key` = 'refine_chapter_system';
UPDATE `testgen_stage` SET `stage_name` = '用例精修-全局', `description` = '需求+大纲+全量用例做最终去重合并'
    WHERE `stage_key` = 'refine_global_system';
UPDATE `testgen_stage` SET `description` = '基于章节大纲直接生成测试用例'
    WHERE `stage_key` = 'case_gen_system';

-- 契约变更：清除旧绑定，启动时重新导入新版官方提示词
DELETE FROM `testgen_prompt`
    WHERE `stage_key` IN ('case_gen_system', 'refine_chapter_system', 'refine_global_system');
