-- =============================================
-- V3: 用例生成
--   任务表 + AI 模型配置 + 提示词在线配置(阶段字典/提示词元数据，内容存 MinIO)。
-- =============================================

-- 测试用例生成任务表
CREATE TABLE IF NOT EXISTS `testgen_task` (
    `id`               int PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    `prd_name`         varchar(200) NOT NULL COMMENT '需求文档名称(UPLOAD为主文件名,LINK为链接)',
    `prd_source`       varchar(16) NOT NULL DEFAULT 'UPLOAD' COMMENT '需求来源: UPLOAD 上传 / LINK 文档链接',
    `parse_image`      tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否解析文档内图片(0否1是)',
    `parse_sub_doc`    tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否解析二级文档(仅LINK生效)',
    `prd_display_name` varchar(512) NULL COMMENT '需求文档展示名(LINK来源为文档真实标题)',
    `related_names`    varchar(2048) NULL COMMENT '关联文档文件名(换行拼接,仅UPLOAD来源)',
    `status`           enum('NEW', 'PLANNING', 'PLAN_REVIEW', 'GENERATING', 'EDITING', 'FINISHED', 'FAILED') DEFAULT 'NEW' COMMENT '状态',
    `message`          text COMMENT '提示信息',
    `xmind_file_name`  varchar(200) COMMENT '导出的XMind文件名(MinIO object key)',
    `creator`          varchar(50) COMMENT '创建人',
    `create_time`      datetime NOT NULL COMMENT '创建时间',
    `update_time`      datetime NOT NULL COMMENT '更新时间',
    INDEX idx_status (`status`),
    INDEX idx_create_time (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='测试用例生成任务表';

-- AI 模型在线配置表
CREATE TABLE IF NOT EXISTS `ai_model` (
    `id`              int unsigned auto_increment COMMENT '主键ID',
    `name`            varchar(64)  NOT NULL COMMENT '模型显示名称',
    `base_url`        varchar(256) NOT NULL COMMENT 'OpenAI 兼容接口 base-url',
    `api_key`         varchar(512) NOT NULL COMMENT '接口密钥',
    `model_name`      varchar(128) NOT NULL COMMENT '实际模型标识,如 gpt-5.5',
    `use_as_vision`   tinyint(1)   NOT NULL DEFAULT 0 COMMENT '是否用作 vision 模型(全局唯一生效)',
    `use_as_thinking` tinyint(1)   NOT NULL DEFAULT 0 COMMENT '是否用作 thinking 模型(全局唯一生效)',
    `remark`          varchar(256) COMMENT '备注',
    `create_time`     datetime NOT NULL DEFAULT current_timestamp COMMENT '创建时间',
    `update_time`     datetime NOT NULL DEFAULT current_timestamp ON UPDATE current_timestamp COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用例生成AI模型配置表';

-- 提示词阶段字典:stage_key 与兜底静态文件名(prompts/xxx.txt)严格一致
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

-- 提示词元数据:内容存 MinIO,stage_key 为空表示未标记的草稿,非空时全局唯一生效
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

-- 阶段字典 seed:精修收敛为两级(章节内去重补漏 + 全局去重合并)
INSERT INTO `testgen_stage` (`stage_key`, `stage_name`, `description`, `sort_no`) VALUES
    ('image_requirement_system','图片需求理解',     '将上传的图片理解为需求内容',              1),
    ('embedded_image_system',   '内嵌图片理解',     '理解文档内嵌图片并回填原文',              2),
    ('consolidate_system',      '需求整合',         '整合汇总需求文档',                        3),
    ('planning_system',         '大纲规划',         '规划测试大纲',                            4),
    ('extract_points_system',   '测试点提取',       '从需求文档中提取测试点',                  5),
    ('refine_chapter_system',   '测试点精修-章节内', '按章节去重并补齐漏点',                    6),
    ('refine_global_system',    '测试点精修-全局',   '需求+大纲+全量测试点做最终去重合并',      7),
    ('case_gen_system',         '用例生成',         '将测试点转换为测试用例',                  8);
