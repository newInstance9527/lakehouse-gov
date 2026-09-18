-- 业务种子：Trino query_gateway 数据源（§37：禁止向 Superset 下发业务库）
-- 凭证明文由 LhPlatformSecretBootstrap / 运维写入 Vault；此处仅 PLACEHOLDER 占位

INSERT IGNORE INTO `ig_datasource` (
  `id`, `revision`, `ws`, `ds_code`, `name`, `type`, `category`,
  `conn_masked`, `endpoint_host`, `endpoint_port`, `database_name`,
  `vault_path`, `status`, `purposes`, `level`, `ver`, `health_score`,
  `create_time`, `delete_flag`
) VALUES (
  'lh_ds_trino_main',
  1,
  'default',
  'ds_trino_main',
  '平台Trino查询网关',
  'trino',
  'dw',
  '{"host":"127.0.0.1","port":18080,"catalog":"iceberg","ssl":true}',
  '127.0.0.1',
  '18080',
  'iceberg',
  'datasource/trino/lh_ds_trino_main',
  'online',
  '["query_gateway"]',
  '内部',
  'v1.0',
  100,
  NOW(),
  'NOT_DELETE'
);

INSERT IGNORE INTO `ig_secret_store` (`vault_path`, `secret_cipher`, `create_time`, `delete_flag`)
VALUES ('datasource/trino/lh_ds_trino_main', 'PLACEHOLDER', NOW(), 'NOT_DELETE');
