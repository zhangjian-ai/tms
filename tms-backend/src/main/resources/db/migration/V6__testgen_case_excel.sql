-- 完成任务时除 XMind 外同时生成用例 Excel，记录其 MinIO 对象名，供前端下载。
ALTER TABLE `testgen_task`
    ADD COLUMN `excel_file_name` varchar(200) NULL COMMENT '导出的用例 Excel 文件名(MinIO object key)' AFTER `xmind_file_name`;
