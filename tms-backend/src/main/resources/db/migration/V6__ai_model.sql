-- =============================================
-- V6: 用例生成 AI 模型在线配置表
-- =============================================

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
