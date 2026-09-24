-- DataGoo OpenAI 兼容对话模型：DeepSeek-V4.1-Flash
-- Key 不入 SQL；由门户 rotate/create 写入 Vault（secret/lakehouse/ai/aim_datagoo_dsflash）

INSERT IGNORE INTO `gov_ai_model`
(`id`,`revision`,`status`,`ws`,`remark`,`name`,`vendor`,`kind`,`model_name`,`base_url`,`vault_path`,`key_mask`,
 `context_tokens`,`price_unit`,`input_rate`,`output_rate`,`enabled`,`egress_kind`,`egress_approved`,
 `latency_ms`,`role_label`,`calls_total`,`cost_total`,`delete_flag`,`create_time`)
VALUES
('aim_datagoo_dsflash',1,'ok','default','OpenAI 兼容 · POST /v1/chat/completions',
 'DeepSeek-V4.1-Flash','DeepSeek','chat','DeepSeek-V4.1-Flash',
 'https://127.0.0.1:3030/v1','secret/lakehouse/ai/aim_datagoo_dsflash','sk-****...y2Q（Vault）',
 '128K','cny_1m',0,0,1,'egress',1,
 0,'默认对话 · DataGoo',0,0.00,'NOT_DELETE',NOW());

-- 先保留旧主模型为 fallback，再切换 primary
UPDATE `gov_ai_route`
SET `fallback_model_id` = CASE
        WHEN `fallback_model_id` IS NULL OR `fallback_model_id` = '' THEN `primary_model_id`
        ELSE `fallback_model_id`
    END,
    `primary_model_id` = 'aim_datagoo_dsflash',
    `revision` = IFNULL(`revision`, 1) + 1,
    `update_time` = NOW()
WHERE `id` IN ('air_manual', 'air_sql', 'air_diag')
  AND `delete_flag` = 'NOT_DELETE';
