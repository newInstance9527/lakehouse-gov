-- BI：指标 → Superset 虚拟数据集绑定（口径 SoT 仍在指标中心；连接 SoT 在数据源中心）

CREATE TABLE IF NOT EXISTS `gov_bi_dataset` (
  `id`                   varchar(20)   NOT NULL COMMENT '主键',
  `revision`             int           NOT NULL DEFAULT 1,
  `status`               varchar(32)   DEFAULT 'active',
  `ws`                   varchar(64)   DEFAULT 'default',
  `remark`               varchar(512)  DEFAULT NULL,
  `metric_code`          varchar(64)   NOT NULL COMMENT '指标编码',
  `ver`                  varchar(16)   NOT NULL COMMENT '钉死版本',
  `ds_id`                varchar(20)   DEFAULT NULL COMMENT '门户 Trino 数据源 id',
  `superset_database_id` bigint        DEFAULT NULL COMMENT 'Superset Database pk',
  `superset_dataset_id`  bigint        DEFAULT NULL COMMENT 'Superset Dataset pk',
  `dataset_name`         varchar(128)  DEFAULT NULL COMMENT 'Superset 数据集名',
  `sql_text`             mediumtext    COMMENT '发布时编译 SQL（trino）',
  `filters_hint`         text          COMMENT '维筛选契约提示 JSON：filters.dims≡params.dims',
  `sync_state`           varchar(32)   DEFAULT 'pending' COMMENT 'synced/stale/error/pending',
  `last_sync_at`         datetime      DEFAULT NULL,
  `last_error`           varchar(512)  DEFAULT NULL,
  `delete_flag`          varchar(32)   DEFAULT 'NOT_DELETE',
  `create_time`          datetime      DEFAULT NULL,
  `create_user`          varchar(20)   DEFAULT NULL,
  `update_time`          datetime      DEFAULT NULL,
  `update_user`          varchar(20)   DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_bi_ds_metric_ver` (`ws`, `metric_code`, `ver`) USING BTREE,
  KEY `idx_bi_ds_sync` (`sync_state`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='BI 指标数据集（Superset 投影）';
