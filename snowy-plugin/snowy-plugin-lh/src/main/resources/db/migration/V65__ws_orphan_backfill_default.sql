-- 存量 ws 回填到 default（空间优先：空/演示域孤儿/无主空间编码）
-- 规格：doc/工作空间.md · 配合 V54 删演示空间、V64 空间优先
-- 哨兵 `_platform`（知识库公用）不改；与 default 唯一键冲突的行跳过保留原值

-- 确保平台默认空间存在
INSERT INTO `gov_ws` (
  `id`, `revision`, `status`, `ws`, `remark`, `ws_code`, `ws_kind`, `name`, `icon`,
  `domain_code`, `cost_center`, `trino_rg`, `preferred_schemas`, `owners`, `detail`,
  `tags_json`, `delete_flag`, `create_time`, `update_time`
) SELECT
  '1908902222000000001', 1, 'active', 'default', NULL, 'default', 'team', '平台默认', '🗂️',
  '平台', 'CC-DEFAULT', 'rg_default', '—', '平台',
  '全局默认归属；未指定或孤儿 ws 的业务数据落此。',
  '[{"text":"默认","cls":"tag-gray"}]', 'NOT_DELETE', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `gov_ws` WHERE `ws_code` = 'default');

INSERT INTO `gov_ws_quota` (
  `id`, `revision`, `status`, `ws`, `ws_code`,
  `storage_quota_tb`, `storage_used_tb`, `cu_quota`, `cu_used`,
  `trino_quota`, `trino_used`, `api_qps_quota`, `api_qps_used`,
  `delete_flag`, `create_time`, `update_time`
) SELECT
  '1908902222000000101', 1, 'ok', 'default', 'default',
  1, 0, 200, 0, 3, 0, 100, 0,
  'NOT_DELETE', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `gov_ws_quota` WHERE `ws_code` = 'default');

-- 用户偏好指向已删/空编码 → default
UPDATE `gov_ws_user_pref`
   SET `current_ws_code` = 'default', `update_time` = NOW()
 WHERE `current_ws_code` IS NULL
    OR TRIM(`current_ws_code`) = ''
    OR `current_ws_code` IN ('ws_trade', 'ws_user', 'ws_goods', 'ws_marketing', 'ws_finance', 'ws_sandbox')
    OR (
         `current_ws_code` <> 'default'
     AND `current_ws_code` <> 'enterprise'
     AND `current_ws_code` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag` = 'NOT_DELETE' AND `status` = 'active')
       );

-- ---------- 资产（唯一键 ws+asset_code）----------
UPDATE `gov_asset` a
LEFT JOIN `gov_asset` b
  ON b.`ws` = 'default' AND b.`asset_code` = a.`asset_code` AND b.`id` <> a.`id`
 AND IFNULL(b.`delete_flag`, 'NOT_DELETE') = 'NOT_DELETE'
   SET a.`ws` = 'default'
 WHERE b.`id` IS NULL
   AND (
         a.`ws` IS NULL OR TRIM(a.`ws`) = ''
      OR a.`ws` IN ('ws_trade', 'ws_user', 'ws_goods', 'ws_marketing', 'ws_finance', 'ws_sandbox')
      OR (
           a.`ws` NOT IN ('default', 'enterprise', '_platform')
       AND a.`ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag` = 'NOT_DELETE' AND `status` = 'active')
         )
       );

UPDATE `gov_asset_source_link`
   SET `ws` = 'default'
 WHERE `ws` IS NULL OR TRIM(`ws`) = ''
    OR `ws` IN ('ws_trade', 'ws_user', 'ws_goods', 'ws_marketing', 'ws_finance', 'ws_sandbox')
    OR (
         `ws` NOT IN ('default', 'enterprise', '_platform')
     AND `ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag` = 'NOT_DELETE' AND `status` = 'active')
       );

-- ---------- 指标 ----------
UPDATE `gov_metric` a
LEFT JOIN `gov_metric` b
  ON b.`ws` = 'default' AND b.`metric_code` = a.`metric_code` AND b.`id` <> a.`id`
 AND IFNULL(b.`delete_flag`, 'NOT_DELETE') = 'NOT_DELETE'
   SET a.`ws` = 'default'
 WHERE b.`id` IS NULL
   AND (
         a.`ws` IS NULL OR TRIM(a.`ws`) = ''
      OR a.`ws` IN ('ws_trade', 'ws_user', 'ws_goods', 'ws_marketing', 'ws_finance', 'ws_sandbox')
      OR (
           a.`ws` NOT IN ('default', 'enterprise', '_platform')
       AND a.`ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag` = 'NOT_DELETE' AND `status` = 'active')
         )
       );

UPDATE `gov_metric_ver`
   SET `ws` = 'default'
 WHERE `ws` IS NULL OR TRIM(`ws`) = ''
    OR `ws` IN ('ws_trade', 'ws_user', 'ws_goods', 'ws_marketing', 'ws_finance', 'ws_sandbox')
    OR (
         `ws` NOT IN ('default', 'enterprise', '_platform')
     AND `ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag` = 'NOT_DELETE' AND `status` = 'active')
       );

UPDATE `gov_metric_materialize`
   SET `ws` = 'default'
 WHERE `ws` IS NULL OR TRIM(`ws`) = ''
    OR `ws` IN ('ws_trade', 'ws_user', 'ws_goods', 'ws_marketing', 'ws_finance', 'ws_sandbox')
    OR (
         `ws` NOT IN ('default', 'enterprise', '_platform')
     AND `ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag` = 'NOT_DELETE' AND `status` = 'active')
       );

UPDATE `gov_metric_sample` a
LEFT JOIN `gov_metric_sample` b
  ON b.`ws` = 'default' AND b.`metric_code` = a.`metric_code` AND b.`sample_dt` = a.`sample_dt` AND b.`id` <> a.`id`
   SET a.`ws` = 'default'
 WHERE b.`id` IS NULL
   AND (
         a.`ws` IS NULL OR TRIM(a.`ws`) = ''
      OR a.`ws` IN ('ws_trade', 'ws_user', 'ws_goods', 'ws_marketing', 'ws_finance', 'ws_sandbox')
      OR (
           a.`ws` NOT IN ('default', 'enterprise', '_platform')
       AND a.`ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag` = 'NOT_DELETE' AND `status` = 'active')
         )
       );

-- ---------- ETL ----------
UPDATE `ig_etl_dag` a
LEFT JOIN `ig_etl_dag` b
  ON b.`ws` = 'default' AND b.`dag_code` = a.`dag_code` AND b.`id` <> a.`id`
 AND IFNULL(b.`delete_flag`, 'NOT_DELETE') = 'NOT_DELETE'
   SET a.`ws` = 'default'
 WHERE b.`id` IS NULL
   AND (
         a.`ws` IS NULL OR TRIM(a.`ws`) = ''
      OR a.`ws` IN ('ws_trade', 'ws_user', 'ws_goods', 'ws_marketing', 'ws_finance', 'ws_sandbox')
      OR (
           a.`ws` NOT IN ('default', 'enterprise', '_platform')
       AND a.`ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag` = 'NOT_DELETE' AND `status` = 'active')
         )
       );

UPDATE `ig_etl_run`
   SET `ws` = 'default'
 WHERE `ws` IS NULL OR TRIM(`ws`) = ''
    OR `ws` IN ('ws_trade', 'ws_user', 'ws_goods', 'ws_marketing', 'ws_finance', 'ws_sandbox')
    OR (
         `ws` NOT IN ('default', 'enterprise', '_platform')
     AND `ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag` = 'NOT_DELETE' AND `status` = 'active')
       );

-- ---------- 申请 / 授权 ----------
UPDATE `apply_ticket`
   SET `ws` = 'default'
 WHERE `ws` IS NULL OR TRIM(`ws`) = ''
    OR `ws` IN ('ws_trade', 'ws_user', 'ws_goods', 'ws_marketing', 'ws_finance', 'ws_sandbox')
    OR (
         `ws` NOT IN ('default', 'enterprise', '_platform')
     AND `ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag` = 'NOT_DELETE' AND `status` = 'active')
       );

UPDATE `sec_auth_grant`
   SET `ws` = 'default'
 WHERE `ws` IS NULL OR TRIM(`ws`) = ''
    OR `ws` IN ('ws_trade', 'ws_user', 'ws_goods', 'ws_marketing', 'ws_finance', 'ws_sandbox')
    OR (
         `ws` NOT IN ('default', 'enterprise', '_platform')
     AND `ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag` = 'NOT_DELETE' AND `status` = 'active')
       );

-- ---------- 血缘 / 质量 ----------
UPDATE `gov_lineage_field_edge` a
LEFT JOIN `gov_lineage_field_edge` b
  ON b.`ws` = 'default'
 AND b.`from_table` = a.`from_table` AND b.`from_field` = a.`from_field`
 AND b.`to_table` = a.`to_table` AND b.`to_field` = a.`to_field`
 AND IFNULL(b.`etl_job_id`, '') = IFNULL(a.`etl_job_id`, '')
 AND b.`id` <> a.`id`
 AND IFNULL(b.`delete_flag`, 'NOT_DELETE') = 'NOT_DELETE'
   SET a.`ws` = 'default'
 WHERE b.`id` IS NULL
   AND (
         a.`ws` IS NULL OR TRIM(a.`ws`) = ''
      OR a.`ws` IN ('ws_trade', 'ws_user', 'ws_goods', 'ws_marketing', 'ws_finance', 'ws_sandbox')
      OR (
           a.`ws` NOT IN ('default', 'enterprise', '_platform')
       AND a.`ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag` = 'NOT_DELETE' AND `status` = 'active')
         )
       );

UPDATE `gov_dq_rule` a
LEFT JOIN `gov_dq_rule` b
  ON b.`ws` = 'default'
 AND b.`table_name` = a.`table_name` AND IFNULL(b.`field_name`, '') = IFNULL(a.`field_name`, '')
 AND b.`rule_code` = a.`rule_code` AND b.`id` <> a.`id`
 AND IFNULL(b.`delete_flag`, 'NOT_DELETE') = 'NOT_DELETE'
   SET a.`ws` = 'default'
 WHERE b.`id` IS NULL
   AND (
         a.`ws` IS NULL OR TRIM(a.`ws`) = ''
      OR a.`ws` IN ('ws_trade', 'ws_user', 'ws_goods', 'ws_marketing', 'ws_finance', 'ws_sandbox')
      OR (
           a.`ws` NOT IN ('default', 'enterprise', '_platform')
       AND a.`ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag` = 'NOT_DELETE' AND `status` = 'active')
         )
       );

UPDATE `gov_dq_rule_run`
   SET `ws` = 'default'
 WHERE `ws` IS NULL OR TRIM(`ws`) = ''
    OR `ws` IN ('ws_trade', 'ws_user', 'ws_goods', 'ws_marketing', 'ws_finance', 'ws_sandbox')
    OR (
         `ws` NOT IN ('default', 'enterprise', '_platform')
     AND `ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag` = 'NOT_DELETE' AND `status` = 'active')
       );

UPDATE `gov_dq_gate`
   SET `ws` = 'default'
 WHERE `ws` IS NULL OR TRIM(`ws`) = ''
    OR `ws` IN ('ws_trade', 'ws_user', 'ws_goods', 'ws_marketing', 'ws_finance', 'ws_sandbox')
    OR (
         `ws` NOT IN ('default', 'enterprise', '_platform')
     AND `ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag` = 'NOT_DELETE' AND `status` = 'active')
       );

-- ---------- 标准 ----------
UPDATE `gov_std_field` a
LEFT JOIN `gov_std_field` b
  ON b.`ws` = 'default' AND b.`field_name` = a.`field_name` AND b.`id` <> a.`id`
 AND IFNULL(b.`delete_flag`, 'NOT_DELETE') = 'NOT_DELETE'
   SET a.`ws` = 'default'
 WHERE b.`id` IS NULL
   AND (
         a.`ws` IS NULL OR TRIM(a.`ws`) = ''
      OR a.`ws` IN ('ws_trade', 'ws_user', 'ws_goods', 'ws_marketing', 'ws_finance', 'ws_sandbox')
      OR (
           a.`ws` NOT IN ('default', 'enterprise', '_platform')
       AND a.`ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag` = 'NOT_DELETE' AND `status` = 'active')
         )
       );

UPDATE `gov_std_code` a
LEFT JOIN `gov_std_code` b
  ON b.`ws` = 'default' AND b.`code_set_id` = a.`code_set_id` AND b.`id` <> a.`id`
 AND IFNULL(b.`delete_flag`, 'NOT_DELETE') = 'NOT_DELETE'
   SET a.`ws` = 'default'
 WHERE b.`id` IS NULL
   AND (
         a.`ws` IS NULL OR TRIM(a.`ws`) = ''
      OR a.`ws` IN ('ws_trade', 'ws_user', 'ws_goods', 'ws_marketing', 'ws_finance', 'ws_sandbox')
      OR (
           a.`ws` NOT IN ('default', 'enterprise', '_platform')
       AND a.`ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag` = 'NOT_DELETE' AND `status` = 'active')
         )
       );

UPDATE `gov_std_naming` a
LEFT JOIN `gov_std_naming` b
  ON b.`ws` = 'default' AND b.`layer` = a.`layer` AND b.`pattern` = a.`pattern` AND b.`id` <> a.`id`
 AND IFNULL(b.`delete_flag`, 'NOT_DELETE') = 'NOT_DELETE'
   SET a.`ws` = 'default'
 WHERE b.`id` IS NULL
   AND (
         a.`ws` IS NULL OR TRIM(a.`ws`) = ''
      OR a.`ws` IN ('ws_trade', 'ws_user', 'ws_goods', 'ws_marketing', 'ws_finance', 'ws_sandbox')
      OR (
           a.`ws` NOT IN ('default', 'enterprise', '_platform')
       AND a.`ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag` = 'NOT_DELETE' AND `status` = 'active')
         )
       );

UPDATE `gov_std_mapping` a
LEFT JOIN `gov_std_mapping` b
  ON b.`ws` = 'default'
 AND b.`src_object` = a.`src_object` AND b.`src_field` = a.`src_field`
 AND b.`target_table` = a.`target_table` AND b.`std_field_name` = a.`std_field_name`
 AND b.`id` <> a.`id`
 AND IFNULL(b.`delete_flag`, 'NOT_DELETE') = 'NOT_DELETE'
   SET a.`ws` = 'default'
 WHERE b.`id` IS NULL
   AND (
         a.`ws` IS NULL OR TRIM(a.`ws`) = ''
      OR a.`ws` IN ('ws_trade', 'ws_user', 'ws_goods', 'ws_marketing', 'ws_finance', 'ws_sandbox')
      OR (
           a.`ws` NOT IN ('default', 'enterprise', '_platform')
       AND a.`ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag` = 'NOT_DELETE' AND `status` = 'active')
         )
       );

UPDATE `gov_std_detect_result`
   SET `ws` = 'default'
 WHERE `ws` IS NULL OR TRIM(`ws`) = ''
    OR `ws` IN ('ws_trade', 'ws_user', 'ws_goods', 'ws_marketing', 'ws_finance', 'ws_sandbox')
    OR (
         `ws` NOT IN ('default', 'enterprise', '_platform')
     AND `ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag` = 'NOT_DELETE' AND `status` = 'active')
       );

-- ---------- 生命周期 / 存储 ----------
UPDATE `gov_lc_policy` a
LEFT JOIN `gov_lc_policy` b
  ON b.`ws` = 'default' AND b.`table_fqn` = a.`table_fqn` AND b.`id` <> a.`id`
 AND IFNULL(b.`delete_flag`, 'NOT_DELETE') = 'NOT_DELETE'
   SET a.`ws` = 'default'
 WHERE b.`id` IS NULL
   AND (
         a.`ws` IS NULL OR TRIM(a.`ws`) = ''
      OR a.`ws` IN ('ws_trade', 'ws_user', 'ws_goods', 'ws_marketing', 'ws_finance', 'ws_sandbox')
      OR (
           a.`ws` NOT IN ('default', 'enterprise', '_platform')
       AND a.`ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag` = 'NOT_DELETE' AND `status` = 'active')
         )
       );

UPDATE `gov_lc_table_stat` a
LEFT JOIN `gov_lc_table_stat` b
  ON b.`ws` = 'default' AND b.`table_fqn` = a.`table_fqn` AND b.`id` <> a.`id`
 AND IFNULL(b.`delete_flag`, 'NOT_DELETE') = 'NOT_DELETE'
   SET a.`ws` = 'default'
 WHERE b.`id` IS NULL
   AND (
         a.`ws` IS NULL OR TRIM(a.`ws`) = ''
      OR a.`ws` IN ('ws_trade', 'ws_user', 'ws_goods', 'ws_marketing', 'ws_finance', 'ws_sandbox')
      OR (
           a.`ws` NOT IN ('default', 'enterprise', '_platform')
       AND a.`ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag` = 'NOT_DELETE' AND `status` = 'active')
         )
       );

UPDATE `gov_lc_run` SET `ws` = 'default'
 WHERE `ws` IS NULL OR TRIM(`ws`) = '' OR `ws` IN ('ws_trade','ws_user','ws_goods','ws_marketing','ws_finance','ws_sandbox')
    OR (`ws` NOT IN ('default','enterprise','_platform') AND `ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag`='NOT_DELETE' AND `status`='active'));

UPDATE `gov_lc_orphan_scan` SET `ws` = 'default'
 WHERE `ws` IS NULL OR TRIM(`ws`) = '' OR `ws` IN ('ws_trade','ws_user','ws_goods','ws_marketing','ws_finance','ws_sandbox')
    OR (`ws` NOT IN ('default','enterprise','_platform') AND `ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag`='NOT_DELETE' AND `status`='active'));

UPDATE `gov_lc_job_step` SET `ws` = 'default'
 WHERE `ws` IS NULL OR TRIM(`ws`) = '' OR `ws` IN ('ws_trade','ws_user','ws_goods','ws_marketing','ws_finance','ws_sandbox')
    OR (`ws` NOT IN ('default','enterprise','_platform') AND `ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag`='NOT_DELETE' AND `status`='active'));

UPDATE `gov_lc_storage_advice` SET `ws` = 'default'
 WHERE `ws` IS NULL OR TRIM(`ws`) = '' OR `ws` IN ('ws_trade','ws_user','ws_goods','ws_marketing','ws_finance','ws_sandbox')
    OR (`ws` NOT IN ('default','enterprise','_platform') AND `ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag`='NOT_DELETE' AND `status`='active'));

UPDATE `gov_lc_storage_change_point` SET `ws` = 'default'
 WHERE `ws` IS NULL OR TRIM(`ws`) = '' OR `ws` IN ('ws_trade','ws_user','ws_goods','ws_marketing','ws_finance','ws_sandbox')
    OR (`ws` NOT IN ('default','enterprise','_platform') AND `ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag`='NOT_DELETE' AND `status`='active'));

-- ---------- 数据服务 / 查询 / 脚本 ----------
UPDATE `dataapi_api_binding` a
LEFT JOIN `dataapi_api_binding` b
  ON b.`ws` = 'default' AND b.`public_path` = a.`public_path` AND b.`method` = a.`method` AND b.`id` <> a.`id`
 AND IFNULL(b.`delete_flag`, 'NOT_DELETE') = 'NOT_DELETE'
   SET a.`ws` = 'default'
 WHERE b.`id` IS NULL
   AND (
         a.`ws` IS NULL OR TRIM(a.`ws`) = ''
      OR a.`ws` IN ('ws_trade', 'ws_user', 'ws_goods', 'ws_marketing', 'ws_finance', 'ws_sandbox')
      OR (
           a.`ws` NOT IN ('default', 'enterprise', '_platform')
       AND a.`ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag` = 'NOT_DELETE' AND `status` = 'active')
         )
       );

UPDATE `dataapi_api_key_meta` SET `ws` = 'default'
 WHERE `ws` IS NULL OR TRIM(`ws`) = '' OR `ws` IN ('ws_trade','ws_user','ws_goods','ws_marketing','ws_finance','ws_sandbox')
    OR (`ws` NOT IN ('default','enterprise','_platform') AND `ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag`='NOT_DELETE' AND `status`='active'));

UPDATE `cp_query_exec` SET `ws` = 'default'
 WHERE `ws` IS NULL OR TRIM(`ws`) = '' OR `ws` IN ('ws_trade','ws_user','ws_goods','ws_marketing','ws_finance','ws_sandbox')
    OR (`ws` NOT IN ('default','enterprise','_platform') AND `ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag`='NOT_DELETE' AND `status`='active'));

UPDATE `cp_query_saved` SET `ws` = 'default'
 WHERE `ws` IS NULL OR TRIM(`ws`) = '' OR `ws` IN ('ws_trade','ws_user','ws_goods','ws_marketing','ws_finance','ws_sandbox')
    OR (`ws` NOT IN ('default','enterprise','_platform') AND `ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag`='NOT_DELETE' AND `status`='active'));

UPDATE `cp_query_dataset` a
LEFT JOIN `cp_query_dataset` b
  ON b.`ws` = 'default' AND b.`ds_code` = a.`ds_code` AND b.`id` <> a.`id`
 AND IFNULL(b.`delete_flag`, 'NOT_DELETE') = 'NOT_DELETE'
   SET a.`ws` = 'default'
 WHERE b.`id` IS NULL
   AND (
         a.`ws` IS NULL OR TRIM(a.`ws`) = ''
      OR a.`ws` IN ('ws_trade', 'ws_user', 'ws_goods', 'ws_marketing', 'ws_finance', 'ws_sandbox')
      OR (
           a.`ws` NOT IN ('default', 'enterprise', '_platform')
       AND a.`ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag` = 'NOT_DELETE' AND `status` = 'active')
         )
       );

UPDATE `cp_script_index` a
LEFT JOIN `cp_script_index` b
  ON b.`ws` = 'default' AND b.`path` = a.`path` AND b.`id` <> a.`id`
 AND IFNULL(b.`delete_flag`, 'NOT_DELETE') = 'NOT_DELETE'
   SET a.`ws` = 'default'
 WHERE b.`id` IS NULL
   AND (
         a.`ws` IS NULL OR TRIM(a.`ws`) = ''
      OR a.`ws` IN ('ws_trade', 'ws_user', 'ws_goods', 'ws_marketing', 'ws_finance', 'ws_sandbox')
      OR (
           a.`ws` NOT IN ('default', 'enterprise', '_platform')
       AND a.`ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag` = 'NOT_DELETE' AND `status` = 'active')
         )
       );

UPDATE `cp_script_run` SET `ws` = 'default'
 WHERE `ws` IS NULL OR TRIM(`ws`) = '' OR `ws` IN ('ws_trade','ws_user','ws_goods','ws_marketing','ws_finance','ws_sandbox')
    OR (`ws` NOT IN ('default','enterprise','_platform') AND `ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag`='NOT_DELETE' AND `status`='active'));

UPDATE `cp_release` SET `ws` = 'default'
 WHERE `ws` IS NULL OR TRIM(`ws`) = '' OR `ws` IN ('ws_trade','ws_user','ws_goods','ws_marketing','ws_finance','ws_sandbox')
    OR (`ws` NOT IN ('default','enterprise','_platform') AND `ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag`='NOT_DELETE' AND `status`='active'));

-- ---------- 契约 / 观测 / 出湖 / 漂移 ----------
UPDATE `gov_contract_schema` a
LEFT JOIN `gov_contract_schema` b
  ON b.`ws` = 'default' AND b.`name` = a.`name` AND b.`id` <> a.`id`
 AND IFNULL(b.`delete_flag`, 'NOT_DELETE') = 'NOT_DELETE'
   SET a.`ws` = 'default'
 WHERE b.`id` IS NULL
   AND (
         a.`ws` IS NULL OR TRIM(a.`ws`) = ''
      OR a.`ws` IN ('ws_trade', 'ws_user', 'ws_goods', 'ws_marketing', 'ws_finance', 'ws_sandbox')
      OR (
           a.`ws` NOT IN ('default', 'enterprise', '_platform')
       AND a.`ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag` = 'NOT_DELETE' AND `status` = 'active')
         )
       );

UPDATE `gov_contract_schema_ver` SET `ws` = 'default'
 WHERE `ws` IS NULL OR TRIM(`ws`) = '' OR `ws` IN ('ws_trade','ws_user','ws_goods','ws_marketing','ws_finance','ws_sandbox')
    OR (`ws` NOT IN ('default','enterprise','_platform') AND `ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag`='NOT_DELETE' AND `status`='active'));

UPDATE `gov_contract_change` SET `ws` = 'default'
 WHERE `ws` IS NULL OR TRIM(`ws`) = '' OR `ws` IN ('ws_trade','ws_user','ws_goods','ws_marketing','ws_finance','ws_sandbox')
    OR (`ws` NOT IN ('default','enterprise','_platform') AND `ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag`='NOT_DELETE' AND `status`='active'));

UPDATE `gov_contract_cdc_cfg` a
LEFT JOIN `gov_contract_cdc_cfg` b
  ON b.`ws` = 'default' AND b.`topic` = a.`topic` AND b.`id` <> a.`id`
 AND IFNULL(b.`delete_flag`, 'NOT_DELETE') = 'NOT_DELETE'
   SET a.`ws` = 'default'
 WHERE b.`id` IS NULL
   AND (
         a.`ws` IS NULL OR TRIM(a.`ws`) = ''
      OR a.`ws` IN ('ws_trade', 'ws_user', 'ws_goods', 'ws_marketing', 'ws_finance', 'ws_sandbox')
      OR (
           a.`ws` NOT IN ('default', 'enterprise', '_platform')
       AND a.`ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag` = 'NOT_DELETE' AND `status` = 'active')
         )
       );

UPDATE `gov_obs_span` SET `ws` = 'default'
 WHERE `ws` IS NULL OR TRIM(`ws`) = '' OR `ws` IN ('ws_trade','ws_user','ws_goods','ws_marketing','ws_finance','ws_sandbox')
    OR (`ws` NOT IN ('default','enterprise','_platform') AND `ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag`='NOT_DELETE' AND `status`='active'));

UPDATE `gov_export_audit` SET `ws` = 'default'
 WHERE `ws` IS NULL OR TRIM(`ws`) = '' OR `ws` IN ('ws_trade','ws_user','ws_goods','ws_marketing','ws_finance','ws_sandbox')
    OR (`ws` NOT IN ('default','enterprise','_platform') AND `ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag`='NOT_DELETE' AND `status`='active'));

UPDATE `recon_meta_drift` SET `ws` = 'default'
 WHERE `ws` IS NULL OR TRIM(`ws`) = '' OR `ws` IN ('ws_trade','ws_user','ws_goods','ws_marketing','ws_finance','ws_sandbox')
    OR (`ws` NOT IN ('default','enterprise','_platform') AND `ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag`='NOT_DELETE' AND `status`='active'));

UPDATE `gov_lineage_change_eval` SET `ws` = 'default'
 WHERE `ws` IS NULL OR TRIM(`ws`) = '' OR `ws` IN ('ws_trade','ws_user','ws_goods','ws_marketing','ws_finance','ws_sandbox')
    OR (`ws` NOT IN ('default','enterprise','_platform') AND `ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag`='NOT_DELETE' AND `status`='active'));

UPDATE `gov_lineage_ddl_block` SET `ws` = 'default'
 WHERE `ws` IS NULL OR TRIM(`ws`) = '' OR `ws` IN ('ws_trade','ws_user','ws_goods','ws_marketing','ws_finance','ws_sandbox')
    OR (`ws` NOT IN ('default','enterprise','_platform') AND `ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag`='NOT_DELETE' AND `status`='active'));

-- ---------- 合规删除 / 脱敏投影 / Trino map ----------
UPDATE `gov_del_subject_map` a
LEFT JOIN `gov_del_subject_map` b
  ON b.`ws` = 'default' AND b.`subject_type` = a.`subject_type` AND b.`object_fqn` = a.`object_fqn` AND b.`id` <> a.`id`
 AND IFNULL(b.`delete_flag`, 'NOT_DELETE') = 'NOT_DELETE'
   SET a.`ws` = 'default'
 WHERE b.`id` IS NULL
   AND (
         a.`ws` IS NULL OR TRIM(a.`ws`) = ''
      OR a.`ws` IN ('ws_trade', 'ws_user', 'ws_goods', 'ws_marketing', 'ws_finance', 'ws_sandbox')
      OR (
           a.`ws` NOT IN ('default', 'enterprise', '_platform')
       AND a.`ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag` = 'NOT_DELETE' AND `status` = 'active')
         )
       );

UPDATE `gov_del_request` SET `ws` = 'default'
 WHERE `ws` IS NULL OR TRIM(`ws`) = '' OR `ws` IN ('ws_trade','ws_user','ws_goods','ws_marketing','ws_finance','ws_sandbox')
    OR (`ws` NOT IN ('default','enterprise','_platform') AND `ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag`='NOT_DELETE' AND `status`='active'));

UPDATE `gov_del_target` SET `ws` = 'default'
 WHERE `ws` IS NULL OR TRIM(`ws`) = '' OR `ws` IN ('ws_trade','ws_user','ws_goods','ws_marketing','ws_finance','ws_sandbox')
    OR (`ws` NOT IN ('default','enterprise','_platform') AND `ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag`='NOT_DELETE' AND `status`='active'));

UPDATE `gov_del_exec` SET `ws` = 'default'
 WHERE `ws` IS NULL OR TRIM(`ws`) = '' OR `ws` IN ('ws_trade','ws_user','ws_goods','ws_marketing','ws_finance','ws_sandbox')
    OR (`ws` NOT IN ('default','enterprise','_platform') AND `ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag`='NOT_DELETE' AND `status`='active'));

UPDATE `gov_del_evidence` SET `ws` = 'default'
 WHERE `ws` IS NULL OR TRIM(`ws`) = '' OR `ws` IN ('ws_trade','ws_user','ws_goods','ws_marketing','ws_finance','ws_sandbox')
    OR (`ws` NOT IN ('default','enterprise','_platform') AND `ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag`='NOT_DELETE' AND `status`='active'));

UPDATE `gov_del_hold` SET `ws` = 'default'
 WHERE `ws` IS NULL OR TRIM(`ws`) = '' OR `ws` IN ('ws_trade','ws_user','ws_goods','ws_marketing','ws_finance','ws_sandbox')
    OR (`ws` NOT IN ('default','enterprise','_platform') AND `ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag`='NOT_DELETE' AND `status`='active'));

UPDATE `gov_del_subject_dek` a
LEFT JOIN `gov_del_subject_dek` b
  ON b.`ws` = 'default' AND b.`subject_id_hash` = a.`subject_id_hash`
 AND b.`object_fqn` = a.`object_fqn` AND b.`column_name` = a.`column_name` AND b.`id` <> a.`id`
 AND IFNULL(b.`delete_flag`, 'NOT_DELETE') = 'NOT_DELETE'
   SET a.`ws` = 'default'
 WHERE b.`id` IS NULL
   AND (
         a.`ws` IS NULL OR TRIM(a.`ws`) = ''
      OR a.`ws` IN ('ws_trade', 'ws_user', 'ws_goods', 'ws_marketing', 'ws_finance', 'ws_sandbox')
      OR (
           a.`ws` NOT IN ('default', 'enterprise', '_platform')
       AND a.`ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag` = 'NOT_DELETE' AND `status` = 'active')
         )
       );

UPDATE `sec_mask_policy_proj` SET `ws` = 'default'
 WHERE `ws` IS NULL OR TRIM(`ws`) = '' OR `ws` IN ('ws_trade','ws_user','ws_goods','ws_marketing','ws_finance','ws_sandbox')
    OR (`ws` NOT IN ('default','enterprise','_platform') AND `ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag`='NOT_DELETE' AND `status`='active'));

UPDATE `cb_trino_catalog_map` a
LEFT JOIN `cb_trino_catalog_map` b
  ON b.`ws` = 'default' AND b.`grav_catalog` = a.`grav_catalog` AND b.`id` <> a.`id`
 AND IFNULL(b.`delete_flag`, 'NOT_DELETE') = 'NOT_DELETE'
   SET a.`ws` = 'default'
 WHERE b.`id` IS NULL
   AND (
         a.`ws` IS NULL OR TRIM(a.`ws`) = ''
      OR a.`ws` IN ('ws_trade', 'ws_user', 'ws_goods', 'ws_marketing', 'ws_finance', 'ws_sandbox')
      OR (
           a.`ws` NOT IN ('default', 'enterprise', '_platform')
       AND a.`ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag` = 'NOT_DELETE' AND `status` = 'active')
         )
       );

-- ---------- AI / 知识库（保留 _platform）----------
UPDATE `gov_ai_model` SET `ws` = 'default'
 WHERE (`ws` IS NULL OR TRIM(`ws`) = '' OR `ws` IN ('ws_trade','ws_user','ws_goods','ws_marketing','ws_finance','ws_sandbox')
    OR (`ws` NOT IN ('default','enterprise','_platform') AND `ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag`='NOT_DELETE' AND `status`='active')))
   AND IFNULL(`ws`, '') <> '_platform';

UPDATE `gov_ai_route` SET `ws` = 'default'
 WHERE (`ws` IS NULL OR TRIM(`ws`) = '' OR `ws` IN ('ws_trade','ws_user','ws_goods','ws_marketing','ws_finance','ws_sandbox')
    OR (`ws` NOT IN ('default','enterprise','_platform') AND `ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag`='NOT_DELETE' AND `status`='active')))
   AND IFNULL(`ws`, '') <> '_platform';

-- 路由作用域若仍指向已删演示空间，一并回落
UPDATE `gov_ai_route`
   SET `ws_scope` = 'default'
 WHERE `ws_scope` IN ('ws_trade', 'ws_user', 'ws_goods', 'ws_marketing', 'ws_finance', 'ws_sandbox');

UPDATE `gov_ai_usage_daily` SET `ws` = 'default'
 WHERE (`ws` IS NULL OR TRIM(`ws`) = '' OR `ws` IN ('ws_trade','ws_user','ws_goods','ws_marketing','ws_finance','ws_sandbox')
    OR (`ws` NOT IN ('default','enterprise','_platform') AND `ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag`='NOT_DELETE' AND `status`='active')))
   AND IFNULL(`ws`, '') <> '_platform';

UPDATE `gov_ai_session` SET `ws` = 'default'
 WHERE (`ws` IS NULL OR TRIM(`ws`) = '' OR `ws` IN ('ws_trade','ws_user','ws_goods','ws_marketing','ws_finance','ws_sandbox')
    OR (`ws` NOT IN ('default','enterprise','_platform') AND `ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag`='NOT_DELETE' AND `status`='active')))
   AND IFNULL(`ws`, '') <> '_platform';

UPDATE `gov_kb_entry` SET `ws` = 'default'
 WHERE (`ws` IS NULL OR TRIM(`ws`) = '' OR `ws` IN ('ws_trade','ws_user','ws_goods','ws_marketing','ws_finance','ws_sandbox')
    OR (`ws` NOT IN ('default','enterprise','_platform') AND `ws` NOT IN (SELECT `ws_code` FROM `gov_ws` WHERE `delete_flag`='NOT_DELETE' AND `status`='active')))
   AND IFNULL(`ws`, '') <> '_platform';
