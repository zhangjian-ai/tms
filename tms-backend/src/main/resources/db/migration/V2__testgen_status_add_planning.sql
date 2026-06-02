-- 用例生成任务状态枚举增加规划阶段值
-- 新增 PLANNING（规划中）和 PLAN_REVIEW（等待大纲确认）两个状态，
-- 由 generatePoints 拆分为"先规划后确认"流程引入。
-- 顺序按状态机演进排列：NEW -> PLANNING -> PLAN_REVIEW -> GENERATING -> EDITING -> FINISHED/FAILED

ALTER TABLE `test_gen_task`
    MODIFY COLUMN `status` enum('NEW', 'PLANNING', 'PLAN_REVIEW', 'GENERATING', 'EDITING', 'FINISHED', 'FAILED')
    DEFAULT 'NEW' COMMENT '状态';
