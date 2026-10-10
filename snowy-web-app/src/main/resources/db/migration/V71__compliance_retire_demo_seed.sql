-- 下线 V21 合规删除演示工单 / 演示主体 hash / 演示载体索引；空列表合法

DELETE FROM `gov_del_evidence`
WHERE `id` IN ('21401','21402')
   OR `req_id` IN ('21101','21102','21103','21104','21105','21106');

DELETE FROM `gov_del_exec`
WHERE `id` IN ('21301','21302','21303','21304')
   OR `req_id` IN ('21101','21102','21103','21104','21105','21106');

DELETE FROM `gov_del_hold`
WHERE `id` IN ('21501')
   OR `req_id` IN ('21101','21102','21103','21104','21105','21106');

DELETE FROM `gov_del_target`
WHERE `id` LIKE '212%'
   OR `req_id` IN ('21101','21102','21103','21104','21105','21106');

DELETE FROM `gov_del_request`
WHERE `id` IN ('21101','21102','21103','21104','21105','21106')
   OR `req_no` LIKE 'DEL-2026-00%';

UPDATE `gov_del_subject_map`
SET `delete_flag` = 'DELETED',
    `status` = 'inactive',
    `update_time` = NOW()
WHERE `id` IN (
  '21001','21002','21003','21004','21005','21006','21007','21008',
  '21009','21010','21011','21012','21013','21014','21015'
);
