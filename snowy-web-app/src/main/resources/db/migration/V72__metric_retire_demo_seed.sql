-- 下线 V16/V41 指标中心演示种子；门户仅展示真实 gov_metric*（空列表合法）
UPDATE `gov_metric_materialize`
SET `delete_flag` = 'DELETED',
    `status` = 'inactive',
    `update_time` = NOW()
WHERE `id` = 'gmm01'
   OR (`metric_code` = 'M-0001' AND `job_ref` LIKE 'seed.%');

UPDATE `gov_metric`
SET `delete_flag` = 'DELETED',
    `status` = 'deprecated',
    `update_time` = NOW()
WHERE `id` IN ('gm01','gm02','gm03','gm04','gm05')
   OR `metric_code` IN ('A-0012','A-0201','M-0001','C-0035','A-0301');

UPDATE `gov_metric_ver`
SET `delete_flag` = 'DELETED',
    `status` = 'inactive',
    `update_time` = NOW()
WHERE `id` IN ('gmv01','gmv02','gmv03','gmv04','gmv05')
   OR `fingerprint` LIKE 'seed-%';

DELETE FROM `gov_metric_dep`
WHERE `id` IN ('gmd01','gmd02','gmd03')
   OR `ver_id` IN ('gmv01','gmv02','gmv03','gmv04','gmv05');

DELETE FROM `gov_metric_history`
WHERE `id` IN ('gmh01','gmh02','gmh03','gmh04')
   OR `metric_id` IN ('gm01','gm02','gm03','gm04','gm05');

-- V47 对账演示流水（挂 M-0001）
DELETE FROM `recon_partition`
WHERE `id` = 'rpseed01'
   OR (`metric_code` = 'M-0001' AND `trace_id` LIKE 'trc-j5-seed%');
