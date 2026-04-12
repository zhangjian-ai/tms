CREATE TABLE IF NOT EXISTS `api`  (
    `id` int unsigned auto_increment  COMMENT '主键ID',
    `name` varchar(48) NOT NULL COMMENT '接口名称',
    `proto` varchar(16) NOT NULL COMMENT '接口协议',
    `url` varchar(256) NOT NULL COMMENT '请求地址',
    `method` enum('GET', 'POST', 'PUT', 'DELETE') default 'POST' COMMENT '请求方式',
    `create_time` datetime NOT NULL COMMENT '创建时间',
    `update_time` datetime on update current_timestamp NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

ALTER TABLE `api` ADD UNIQUE idx_unique(`name`);

CREATE TABLE IF NOT EXISTS `perf_test`  (
    `id` int unsigned auto_increment  COMMENT '主键ID',
    `name` varchar(64) NOT NULL COMMENT '任务名称',
    `dataset` varchar(128) COMMENT '数据文件名称',
    `proto` varchar(128) COMMENT '协议文件名称',
    `compress` enum('gzip') COMMENT '数据压缩方式',
    `encryption` enum('xor') COMMENT '数据加密方式',
    `api_invokes` json NOT NULL COMMENT '接口调用数据',
    `report` varchar(128) COMMENT '测试报告文件名称',
    `create_time` datetime NOT NULL COMMENT '创建时间',
    `update_time` datetime on update current_timestamp NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

ALTER TABLE `perf_test` ADD UNIQUE idx_unique(`name`);
