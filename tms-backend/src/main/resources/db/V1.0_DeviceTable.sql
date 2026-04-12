CREATE TABLE IF NOT EXISTS `device`  (
    `id` int unsigned auto_increment  COMMENT '主键ID',
    `name` varchar(48) NOT NULL COMMENT '设备名称',
    `serial` varchar(128) NOT NULL COMMENT '序列号',
    `brand`  varchar(24) COMMENT '品牌',
    `model` varchar(48) COMMENT '型号',
    `device_sys` enum('android', 'ios', 'harmony') default 'android' COMMENT '操作系统',
    `os_version` varchar(24) COMMENT '系统版本',
    `width` smallint NOT NULL COMMENT '屏幕宽度',
    `height` smallint NOT NULL COMMENT '屏幕高度',
    `create_time` datetime NOT NULL COMMENT '创建时间',
    `update_time` datetime on update current_timestamp NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE(`serial`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;


CREATE TABLE IF NOT EXISTS `device_connection`  (
    `id` int unsigned auto_increment  COMMENT '主键ID',
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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;
