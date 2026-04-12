CREATE TABLE IF NOT EXISTS `module`  (
    `id` int unsigned auto_increment  COMMENT '主键ID',
    `name` varchar(48) NOT NULL COMMENT '模块名称',
    `parent_id` int unsigned COMMENT '父模块ID',
    `is_product` tinyint default 0 COMMENT '1表示是',
    `create_time` datetime NOT NULL COMMENT '创建时间',
    `update_time` datetime on update current_timestamp NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `test_case` (
    `id` int unsigned auto_increment  COMMENT '主键ID',
    `name` varchar(48) NOT NULL COMMENT '模块名称',
    `module_id` int unsigned NOT NULL COMMENT '归属模块ID',
    `auto_id` int unsigned COMMENT '自动化ID',
    `priority` tinyint NOT NULL COMMENT '用例优先级',
    `create_time` datetime NOT NULL COMMENT '创建时间',
    `update_time` datetime on update current_timestamp NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `test_step` (
    `id` int unsigned auto_increment  COMMENT '主键ID',
    `detail` varchar(256) NOT NULL COMMENT '步骤详情',
    `result` varchar(256) COMMENT '预期结果',
    `is_condition` tinyint default 0 COMMENT '1表示是',
    `case_id` int unsigned NOT NULL COMMENT '归属用例ID',
    `order` tinyint NOT NULL COMMENT '步骤顺序',
    `create_time` datetime NOT NULL COMMENT '创建时间',
    `update_time` datetime on update current_timestamp NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;
