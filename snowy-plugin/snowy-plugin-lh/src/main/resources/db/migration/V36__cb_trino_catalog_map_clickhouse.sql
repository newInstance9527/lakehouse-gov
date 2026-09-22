-- B6：联邦源 clickhouse 自映射种子（Grav 登记名 = Trino 查询名）
-- 现网仍须在 Trino 挂载 catalog/clickhouse.properties 后，queryable 才会含 clickhouse

INSERT INTO `cb_trino_catalog_map` (
  `id`, `revision`, `status`, `ws`, `remark`, `grav_catalog`, `trino_catalog`,
  `enabled`, `kind`, `delete_flag`, `create_time`, `create_user`, `update_time`, `update_user`
)
SELECT
  '2100000000000000036', 1, 'active', 'default',
  'seed: clickhouse federated self-map (B6)',
  'clickhouse', 'clickhouse', 1, 'federated', 'NOT_DELETE',
  NOW(), 'system', NOW(), 'system'
FROM DUAL
WHERE NOT EXISTS (
  SELECT 1 FROM `cb_trino_catalog_map`
  WHERE `ws` = 'default' AND `grav_catalog` = 'clickhouse' AND `delete_flag` = 'NOT_DELETE'
);
