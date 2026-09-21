-- Grav 登记 catalog → Trino 查询 catalog 映射（即席查询面）

CREATE TABLE IF NOT EXISTS `cb_trino_catalog_map` (
  `id`             varchar(20)   NOT NULL COMMENT '主键',
  `revision`       int           NOT NULL DEFAULT 1 COMMENT '乐观锁',
  `status`         varchar(32)   NOT NULL DEFAULT 'active' COMMENT 'active/disabled',
  `ws`             varchar(64)   DEFAULT 'default' COMMENT '工作空间',
  `remark`         varchar(512)  DEFAULT NULL COMMENT '备注',
  `grav_catalog`   varchar(128)  NOT NULL COMMENT 'Gravitino 登记 catalog',
  `trino_catalog`  varchar(128)  NOT NULL COMMENT 'Trino 查询 catalog',
  `enabled`        tinyint(1)    NOT NULL DEFAULT 1 COMMENT '1=参与即席',
  `kind`           varchar(32)   DEFAULT 'federated' COMMENT 'lake/federated',
  `delete_flag`    varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`    datetime      DEFAULT NULL COMMENT '创建时间',
  `create_user`    varchar(20)   DEFAULT NULL COMMENT '创建用户',
  `update_time`    datetime      DEFAULT NULL COMMENT '更新时间',
  `update_user`    varchar(20)   DEFAULT NULL COMMENT '更新用户',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_cb_trino_cat_grav` (`ws`,`grav_catalog`) USING BTREE,
  KEY `idx_cb_trino_cat_trino` (`trino_catalog`,`enabled`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='投影：Grav登记名→Trino查询名；即席仅认 Trino 实况∩白名单';

-- 湖表自映射（与 lh.trino.lake-catalogs 默认一致）
INSERT INTO `cb_trino_catalog_map` (
  `id`, `revision`, `status`, `ws`, `remark`, `grav_catalog`, `trino_catalog`,
  `enabled`, `kind`, `delete_flag`, `create_time`, `create_user`, `update_time`, `update_user`
)
SELECT
  '2100000000000000031', 1, 'active', 'default',
  'seed: iceberg lake self-map',
  'iceberg', 'iceberg', 1, 'lake', 'NOT_DELETE',
  NOW(), 'system', NOW(), 'system'
FROM DUAL
WHERE NOT EXISTS (
  SELECT 1 FROM `cb_trino_catalog_map`
  WHERE `ws` = 'default' AND `grav_catalog` = 'iceberg' AND `delete_flag` = 'NOT_DELETE'
);
