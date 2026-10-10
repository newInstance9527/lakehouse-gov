-- 下线 V19/V25 生命周期与存储趋势演示种子；空列表/空 KPI 合法，禁止假水位回落
-- 策略种子一并下线（演示 FQN）；真实策略由门户 PUT /lh/lifecycle/policies 写入

DELETE FROM `gov_lc_job_step`
WHERE `id` IN ('19201','19202','19203','19204')
   OR `batch_id` = '19200';

DELETE FROM `gov_lc_run`
WHERE `id` = '19300'
   OR `ds_task_id` LIKE 'ds-demo-%';

DELETE FROM `gov_lc_orphan_scan`
WHERE `id` IN ('19401','19402','19403');

DELETE FROM `gov_lc_table_stat`
WHERE `id` IN ('19101','19102','19103','19104','19105');

DELETE FROM `gov_lc_storage_advice`
WHERE `id` IN ('24001','24002','24003');

DELETE FROM `gov_lc_storage_change_point`
WHERE `id` IN ('24101','24102');

UPDATE `gov_lc_policy`
SET `delete_flag` = 'DELETED',
    `status` = 'inactive',
    `update_time` = NOW()
WHERE `id` IN ('19001','19002','19003','19004','19005','19006','19007','19008');
