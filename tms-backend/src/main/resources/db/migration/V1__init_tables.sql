-- =============================================
-- V1: 初始化所有表
-- =============================================

-- 设备表
CREATE TABLE IF NOT EXISTS `device` (
    `id` int unsigned auto_increment COMMENT '主键ID',
    `name` varchar(48) NOT NULL COMMENT '设备名称',
    `serial` varchar(128) NOT NULL COMMENT '序列号',
    `brand` varchar(24) COMMENT '品牌',
    `model` varchar(48) COMMENT '型号',
    `device_sys` enum('android', 'ios', 'harmony') default 'android' COMMENT '操作系统',
    `os_version` varchar(24) COMMENT '系统版本',
    `width` smallint NOT NULL COMMENT '屏幕宽度',
    `height` smallint NOT NULL COMMENT '屏幕高度',
    `create_time` datetime NOT NULL COMMENT '创建时间',
    `update_time` datetime on update current_timestamp NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE(`serial`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- 设备连接信息表
CREATE TABLE IF NOT EXISTS `device_connection` (
    `id` int unsigned auto_increment COMMENT '主键ID',
    `device_id` int unsigned COMMENT '设备ID',
    `adb_host` varchar(48) COMMENT 'adb服务地址',
    `adb_port` varchar(8) COMMENT 'adb服务端口',
    `proxy_host` varchar(48) NOT NULL COMMENT '代理主机地址',
    `proxy_port` varchar(8) NOT NULL COMMENT '代理服务端口',
    `connection` varchar(64) NOT NULL COMMENT '设备快速连接地址',
    `create_time` datetime NOT NULL COMMENT '创建时间',
    `update_time` datetime on update current_timestamp NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_device_id` FOREIGN KEY (`device_id`) REFERENCES `device` (`id`) ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- 模块表
CREATE TABLE IF NOT EXISTS `module` (
    `id` int unsigned auto_increment COMMENT '主键ID',
    `name` varchar(48) NOT NULL COMMENT '模块名称',
    `parent_id` int unsigned COMMENT '父模块ID',
    `is_product` tinyint default 0 COMMENT '1表示是',
    `create_time` datetime NOT NULL COMMENT '创建时间',
    `update_time` datetime on update current_timestamp NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- 测试用例表
CREATE TABLE IF NOT EXISTS `test_case` (
    `id` int unsigned auto_increment COMMENT '主键ID',
    `name` varchar(48) NOT NULL COMMENT '用例名称',
    `module_id` int unsigned NOT NULL COMMENT '归属模块ID',
    `auto_id` int unsigned COMMENT '自动化ID',
    `priority` tinyint NOT NULL COMMENT '用例优先级',
    `create_time` datetime NOT NULL COMMENT '创建时间',
    `update_time` datetime on update current_timestamp NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- 测试步骤表
CREATE TABLE IF NOT EXISTS `test_step` (
    `id` int unsigned auto_increment COMMENT '主键ID',
    `detail` varchar(256) NOT NULL COMMENT '步骤详情',
    `result` varchar(256) COMMENT '预期结果',
    `is_condition` tinyint default 0 COMMENT '1表示是',
    `case_id` int unsigned NOT NULL COMMENT '归属用例ID',
    `order` tinyint NOT NULL COMMENT '步骤顺序',
    `create_time` datetime NOT NULL COMMENT '创建时间',
    `update_time` datetime on update current_timestamp NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- 接口表
CREATE TABLE IF NOT EXISTS `api` (
    `id` int unsigned auto_increment COMMENT '主键ID',
    `name` varchar(48) NOT NULL COMMENT '接口名称',
    `proto` varchar(16) NOT NULL COMMENT '接口协议',
    `url` varchar(256) NOT NULL COMMENT '请求地址',
    `method` enum('GET', 'POST', 'PUT', 'DELETE') default 'POST' COMMENT '请求方式',
    `create_time` datetime NOT NULL COMMENT '创建时间',
    `update_time` datetime on update current_timestamp NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE idx_unique(`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- 性能测试表
CREATE TABLE IF NOT EXISTS `perf_test` (
    `id` int unsigned auto_increment COMMENT '主键ID',
    `name` varchar(64) NOT NULL COMMENT '任务名称',
    `dataset` varchar(128) COMMENT '数据文件名称',
    `proto` varchar(128) COMMENT '协议文件名称',
    `compress` enum('gzip') COMMENT '数据压缩方式',
    `encryption` enum('xor') COMMENT '数据加密方式',
    `api_invokes` json NOT NULL COMMENT '接口调用数据',
    `report` varchar(128) COMMENT '测试报告文件名称',
    `create_time` datetime NOT NULL COMMENT '创建时间',
    `update_time` datetime on update current_timestamp NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE idx_unique(`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- 用户表
CREATE TABLE IF NOT EXISTS `user` (
    `id` int unsigned auto_increment COMMENT '主键ID',
    `username` varchar(48) NOT NULL COMMENT '用户名称',
    `password` varchar(128) NOT NULL COMMENT '登录密码',
    PRIMARY KEY (`id`),
    UNIQUE(`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- 测试用例生成任务表
CREATE TABLE IF NOT EXISTS `test_gen_task` (
    `id` int PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    `prd_name` varchar(200) NOT NULL COMMENT '需求文档名称',
    `prd_type` enum('BIZ', 'BURY', 'API') DEFAULT 'BIZ' COMMENT '需求类型',
    `status` enum('NEW', 'GENERATING', 'EDITING', 'FINISHED', 'FAILED') default 'NEW' COMMENT '状态',
    `message` text COMMENT '提示信息',
    `xmind_file_name` varchar(200) COMMENT '导出的XMind文件名(MinIO object key)',
    `create_time` datetime NOT NULL COMMENT '创建时间',
    `update_time` datetime NOT NULL COMMENT '更新时间',
    INDEX idx_status (`status`),
    INDEX idx_create_time (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='测试用例生成任务表';
