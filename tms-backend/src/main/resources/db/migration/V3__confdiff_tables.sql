-- =============================================
-- V3: 配置对比(confdiff)相关表
-- =============================================

-- 配置对比-机器表
CREATE TABLE IF NOT EXISTS `config_compare_machine` (
    `id`          int unsigned auto_increment COMMENT '主键ID',
    `name`        varchar(64)  NOT NULL COMMENT '机器名称',
    `host`        varchar(128) NOT NULL COMMENT 'SSH主机地址',
    `port`        int          NOT NULL DEFAULT 22 COMMENT 'SSH端口',
    `username`    varchar(64)  NOT NULL COMMENT 'SSH用户名',
    `auth_type`   enum('password', 'private_key') NOT NULL DEFAULT 'password' COMMENT '鉴权方式',
    `password`    varchar(256) COMMENT '登录密码(password鉴权时使用)',
    `private_key` text         COMMENT '私钥PEM内容(private_key鉴权时使用)',
    `passphrase`  varchar(256) COMMENT '私钥口令(可空)',
    `work_dir`    varchar(256) NOT NULL COMMENT '远程统一工作目录,项目clone至此',
    `remark`      varchar(256) COMMENT '备注',
    `create_time` datetime NOT NULL DEFAULT current_timestamp COMMENT '创建时间',
    `update_time` datetime NOT NULL DEFAULT current_timestamp ON UPDATE current_timestamp COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_host_port_user` (`host`, `port`, `username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='配置对比-机器表';

-- 配置对比-项目表
CREATE TABLE IF NOT EXISTS `config_compare_project` (
    `id`             int unsigned auto_increment COMMENT '主键ID',
    `machine_id`     int unsigned NOT NULL COMMENT '所属机器ID',
    `name`           varchar(64)   NOT NULL COMMENT '项目名称(同时作为clone目录名)',
    `repo_url`       varchar(512)  NOT NULL COMMENT 'git仓库地址',
    `config_paths`   varchar(1024) NOT NULL COMMENT '配置文件路径(相对仓库根,逗号分隔)',
    `default_branch` varchar(128)  NOT NULL DEFAULT 'master' COMMENT '默认分支',
    `remark`         varchar(256)  COMMENT '备注',
    `create_time`    datetime NOT NULL DEFAULT current_timestamp COMMENT '创建时间',
    `update_time`    datetime NOT NULL DEFAULT current_timestamp ON UPDATE current_timestamp COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_machine_name` (`machine_id`, `name`),
    CONSTRAINT `fk_ccp_machine` FOREIGN KEY (`machine_id`) REFERENCES `config_compare_machine` (`id`) ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='配置对比-项目表';

-- 默认机器配置(示例,请按需修改连接信息)
INSERT INTO `config_compare_machine`
    (`name`, `host`, `port`, `username`, `auth_type`, `password`, `work_dir`, `remark`)
VALUES
    ('默认配置机器', '192.168.1.100', 22, 'deploy', 'password', 'CHANGE_ME', '/data/confdiff/repos', '默认示例机器,请按需修改');

-- 默认项目配置(示例)
INSERT INTO `config_compare_project`
    (`machine_id`, `name`, `repo_url`, `config_paths`, `default_branch`, `remark`)
VALUES
    ((SELECT id FROM `config_compare_machine` WHERE `host` = '192.168.1.100' AND `port` = 22 AND `username` = 'deploy'),
     'demo-config', 'git@gitlab.example.com:ops/demo-config.git', 'config,env/excel', 'master', '默认示例项目');
