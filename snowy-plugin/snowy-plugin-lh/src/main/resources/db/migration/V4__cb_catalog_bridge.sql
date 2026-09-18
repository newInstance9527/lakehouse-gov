-- Catalog 桥接：Gravitino → OpenMetadata Schema Sync 指针
CREATE TABLE IF NOT EXISTS `cb_grav_asset_ref` (
  `id`            varchar(20)   NOT NULL COMMENT '主键',
  `revision`      int           NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
  `status`        varchar(32)   DEFAULT 'active' COMMENT 'active/missing/archived',
  `ws`            varchar(64)   DEFAULT 'default' COMMENT '工作空间',
  `remark`        varchar(512)  DEFAULT NULL COMMENT '备注',
  `grav_metalake` varchar(128)  NOT NULL COMMENT 'Gravitino metalake',
  `grav_catalog`  varchar(128)  NOT NULL COMMENT 'Gravitino catalog',
  `grav_schema`   varchar(128)  NOT NULL COMMENT 'Gravitino schema',
  `grav_table`    varchar(256)  NOT NULL COMMENT 'Gravitino table',
  `grav_table_id` varchar(128)  DEFAULT NULL COMMENT 'Gravitino内部ID',
  `grav_revision` bigint        NOT NULL DEFAULT 0 COMMENT '远端revision',
  `location_uri`  varchar(1024) DEFAULT NULL COMMENT '表位置冗余',
  `columns_json`  json          DEFAULT NULL COMMENT '结构列快照(名/类型)',
  `layer`         varchar(16)   DEFAULT NULL COMMENT 'ODS/DWD/DWS/ADS/DIM',
  `domain_code`   varchar(64)   DEFAULT NULL COMMENT '业务域',
  `drift_flag`    tinyint(1)    NOT NULL DEFAULT 0 COMMENT '是否漂移',
  `last_seen_at`  datetime      DEFAULT NULL COMMENT '最近见到时间',
  `delete_flag`   varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`   datetime      DEFAULT NULL COMMENT '创建时间',
  `create_user`   varchar(20)   DEFAULT NULL COMMENT '创建用户',
  `update_time`   datetime      DEFAULT NULL COMMENT '修改时间',
  `update_user`   varchar(20)   DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_cb_grav_asset` (`grav_metalake`,`grav_catalog`,`grav_schema`,`grav_table`) USING BTREE,
  KEY `idx_cb_grav_ws` (`ws`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='投影：Gravitino表指针；结构以Grav为准';

CREATE TABLE IF NOT EXISTS `cb_om_asset_ref` (
  `id`               varchar(20)  NOT NULL COMMENT '主键',
  `revision`         int          NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
  `status`           varchar(32)  DEFAULT 'active' COMMENT 'active/stale/orphaned',
  `ws`               varchar(64)  DEFAULT 'default' COMMENT '工作空间',
  `remark`           varchar(512) DEFAULT NULL COMMENT '备注',
  `grav_asset_id`    varchar(20)  NOT NULL COMMENT 'cb_grav_asset_ref.id',
  `om_fqn`           varchar(512) NOT NULL COMMENT 'OpenMetadata FQN',
  `om_table_id`      varchar(64)  DEFAULT NULL COMMENT 'OM实体ID',
  `om_revision`      varchar(64)  DEFAULT NULL COMMENT 'OM版本戳',
  `synced_grav_rev`  bigint       NOT NULL DEFAULT 0 COMMENT '已同步到OM的grav_revision',
  `last_sync_at`     datetime     DEFAULT NULL COMMENT '最近同步',
  `last_sync_status` varchar(32)  DEFAULT 'never' COMMENT 'never/ok/error/skipped',
  `last_error`       varchar(512) DEFAULT NULL COMMENT '错误信息',
  `drift_flag`       tinyint(1)   NOT NULL DEFAULT 0 COMMENT '是否漂移',
  `delete_flag`      varchar(32)  DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`      datetime     DEFAULT NULL COMMENT '创建时间',
  `create_user`      varchar(20)  DEFAULT NULL COMMENT '创建用户',
  `update_time`      datetime     DEFAULT NULL COMMENT '修改时间',
  `update_user`      varchar(20)  DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_cb_om_fqn` (`om_fqn`) USING BTREE,
  UNIQUE KEY `uq_cb_om_grav` (`grav_asset_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='投影：OM表指针；注释标签以OM为准';

CREATE TABLE IF NOT EXISTS `cb_schema_sync_watermark` (
  `id`            varchar(20)  NOT NULL COMMENT '主键',
  `source_system` varchar(32)  NOT NULL DEFAULT 'gravitino' COMMENT '源系统',
  `mark_key`      varchar(128) NOT NULL COMMENT '水位键',
  `mark_value`    varchar(256) NOT NULL COMMENT '水位值',
  `update_time`   datetime     DEFAULT NULL COMMENT '更新时间',
  `update_user`   varchar(20)  DEFAULT NULL COMMENT '更新用户',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_cb_sync_watermark` (`source_system`,`mark_key`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='技术：Schema同步水位';
