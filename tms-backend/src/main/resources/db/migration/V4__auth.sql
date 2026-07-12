-- =============================================
-- V4: 登录认证
--   纯三方 SSO(无密码)。user 以 (channel, open_id) 为身份键，username 兼作显示名；
--   user_feishu_token 持久化各用户飞书授权凭据，供异步生成线程按创建者取用。
-- =============================================

-- 用户表
CREATE TABLE IF NOT EXISTS `user` (
    `id`       int unsigned auto_increment COMMENT '主键ID',
    `username` varchar(48)  NOT NULL COMMENT '用户名称(兼作显示名)',
    `channel`  varchar(16)  NOT NULL DEFAULT 'FEISHU' COMMENT '登录渠道: FEISHU / 其他三方',
    `open_id`  varchar(64)  NULL COMMENT '三方 open_id',
    `union_id` varchar(64)  NULL COMMENT '三方 union_id',
    `avatar`   varchar(512) NULL COMMENT '头像URL',
    PRIMARY KEY (`id`),
    UNIQUE(`username`),
    UNIQUE KEY `uk_user_channel_open_id` (`channel`, `open_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户表';

-- 用户飞书 token 表
CREATE TABLE IF NOT EXISTS `user_feishu_token` (
    `id`                int unsigned auto_increment COMMENT '主键ID',
    `user_id`           int unsigned NOT NULL COMMENT '用户ID',
    `access_token`      varchar(512) NOT NULL COMMENT '飞书 user_access_token',
    `refresh_token`     varchar(512) COMMENT '飞书 refresh_token',
    `access_expire_at`  datetime COMMENT 'access_token 过期时间',
    `refresh_expire_at` datetime COMMENT 'refresh_token 过期时间',
    `update_time`       datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE(`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户飞书token表';
