-- device_connection 表下线：连接信息（proxyHost/proxyPort/adbHost/adbPort）随占用生灭，
-- 已迁移至 Redis 键 dc_<serial>，不再持久化。外键 fk_device_id 随表一并删除。
DROP TABLE IF EXISTS `device_connection`;
