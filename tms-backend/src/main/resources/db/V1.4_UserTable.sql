CREATE TABLE IF NOT EXISTS `user`  (
    `id` int unsigned auto_increment  COMMENT '主键ID',
    `username` varchar(48) NOT NULL COMMENT '用户名称',
    `password` varchar(128) NOT NULL COMMENT '登录密码',
    PRIMARY KEY (`id`),
    UNIQUE(`username`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;
