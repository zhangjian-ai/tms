-- =============================================
-- V7: 用例生成提示词在线配置
--   testgen_stage  : 阶段字典(阶段名 <-> 阶段key),兜底静态文件命名与 stage_key 严格一致
--   testgen_prompt : 提示词元数据,内容存 MinIO,stage_key 为空表示未标记的草稿
-- =============================================

CREATE TABLE IF NOT EXISTS `testgen_stage` (
    `id`          int unsigned auto_increment COMMENT '主键ID',
    `stage_key`   varchar(64)  NOT NULL COMMENT '阶段key,与兜底提示词文件名严格一致,如 case_gen_system',
    `stage_name`  varchar(64)  NOT NULL COMMENT '阶段显示名',
    `description` varchar(256) COMMENT '阶段说明',
    `sort_no`     int          NOT NULL DEFAULT 0 COMMENT '排序',
    `create_time` datetime NOT NULL DEFAULT current_timestamp COMMENT '创建时间',
    `update_time` datetime NOT NULL DEFAULT current_timestamp ON UPDATE current_timestamp COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_stage_key` (`stage_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用例生成阶段字典';

CREATE TABLE IF NOT EXISTS `testgen_prompt` (
    `id`          int unsigned auto_increment COMMENT '主键ID',
    `name`        varchar(128) NOT NULL COMMENT '提示词显示名',
    `stage_key`   varchar(64)  DEFAULT NULL COMMENT '绑定的阶段key,为空表示未标记草稿;非空时全局唯一生效',
    `object_key`  varchar(256) NOT NULL COMMENT '提示词内容在 MinIO 中的对象键',
    `remark`      varchar(256) COMMENT '备注',
    `create_time` datetime NOT NULL DEFAULT current_timestamp COMMENT '创建时间',
    `update_time` datetime NOT NULL DEFAULT current_timestamp ON UPDATE current_timestamp COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_stage_key` (`stage_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用例生成提示词';

-- 阶段字典 seed:stage_key 与工程中现有系统提示词文件名(prompts/xxx.txt)严格一致
INSERT INTO `testgen_stage` (`stage_key`, `stage_name`, `description`, `sort_no`) VALUES
    ('image_requirement_system','图片需求理解',          '将上传的图片理解为需求内容',            1),
    ('embedded_image_system',   '内嵌图片理解',          '理解文档内嵌图片并回填原文',            2),
    ('consolidate_system',      '需求整合',              '整合汇总需求文档',                      3),
    ('planning_system',         '大纲规划',              '规划测试大纲',                        4),
    ('extract_points_system',   '测试点提取',            '从需求文档中提取测试点',                5),
    ('refine_points_system',    '测试点细化',            '优化每个模块的测试点',                  6),
    ('case_gen_system',         '用例生成',              '将测试点转换为测试用例',                7);
