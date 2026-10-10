-- 下线 V8 质量演示种子（门户只展示真实 /lh/quality 数据；空列表合法）
-- 规则/门禁 soft-delete；演示运行硬删（含 job_run_id=demo-run-*）

UPDATE `gov_dq_rule`
SET `delete_flag` = 'DELETED',
    `enabled` = 0,
    `status` = 'inactive',
    `update_time` = NOW()
WHERE `id` IN ('dq01', 'dq02', 'dq03', 'dq04', 'dq05');

DELETE FROM `gov_dq_rule_run`
WHERE `id` IN ('dr01', 'dr02', 'dr03', 'dr04', 'dr05')
   OR `job_run_id` LIKE 'demo-run-%'
   OR `rule_id` IN ('dq01', 'dq02', 'dq03', 'dq04', 'dq05');

UPDATE `gov_dq_gate`
SET `delete_flag` = 'DELETED',
    `update_time` = NOW()
WHERE `id` IN ('dg01');
