-- 下线 V22 演示域空间/假成员/假配额用量；保留平台 default 归属桶（空成员合法）
-- 规格：doc/工作空间.md · 空库有效，禁止用种子冒充协作数据

DELETE FROM `gov_ws_member`
 WHERE `ws_code` IN ('ws_trade', 'ws_user', 'ws_goods', 'ws_marketing', 'ws_finance', 'ws_sandbox');

DELETE FROM `gov_ws_quota`
 WHERE `ws_code` IN ('ws_trade', 'ws_user', 'ws_goods', 'ws_marketing', 'ws_finance', 'ws_sandbox');

DELETE FROM `gov_ws`
 WHERE `ws_code` IN ('ws_trade', 'ws_user', 'ws_goods', 'ws_marketing', 'ws_finance', 'ws_sandbox');

-- 偏好若指向已删演示空间，回落到 default
UPDATE `gov_ws_user_pref`
   SET `current_ws_code` = 'default',
       `update_time` = NOW()
 WHERE `current_ws_code` IN ('ws_trade', 'ws_user', 'ws_goods', 'ws_marketing', 'ws_finance', 'ws_sandbox');

-- default 配额清零演示用量（保留行本身）
UPDATE `gov_ws_quota`
   SET `storage_used_tb` = 0,
       `cu_used` = 0,
       `trino_used` = 0,
       `api_qps_used` = 0,
       `status` = 'ok',
       `update_time` = NOW()
 WHERE `ws_code` = 'default';
