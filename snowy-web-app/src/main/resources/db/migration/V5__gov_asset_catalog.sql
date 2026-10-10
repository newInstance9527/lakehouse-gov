-- 资产目录：门户登记 SoT + 源绑定；扩展 OM 指针以支持无 Grav 实体（Topic/Index/Container）

CREATE TABLE IF NOT EXISTS `gov_asset` (
  `id`               varchar(20)   NOT NULL COMMENT '主键',
  `revision`         int           NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
  `status`           varchar(32)   NOT NULL DEFAULT 'draft' COMMENT 'draft/active/syncing/degraded/archived',
  `ws`               varchar(64)   DEFAULT 'default' COMMENT '工作空间',
  `remark`           varchar(512)  DEFAULT NULL COMMENT '备注',
  `asset_code`       varchar(128)  NOT NULL COMMENT '对外稳定编码',
  `name`             varchar(256)  NOT NULL COMMENT '展示名',
  `cn_name`          varchar(256)  DEFAULT NULL COMMENT '中文名',
  `description`      varchar(1024) DEFAULT NULL COMMENT '业务说明草稿；人读以OM为准',
  `asset_kind`       varchar(32)   NOT NULL DEFAULT 'table' COMMENT 'table/topic/index/container/queue/path/other',
  `layer`            varchar(16)   NOT NULL COMMENT 'ods/dwd/dws/ads/dim',
  `domain_code`      varchar(64)   NOT NULL COMMENT '业务域编码',
  `sensitivity`      varchar(32)   DEFAULT 'internal' COMMENT 'public/internal/secret/confidential',
  `tech_owner`       varchar(64)   DEFAULT NULL COMMENT '技术负责人(门户草稿)',
  `biz_owner`        varchar(64)   DEFAULT NULL COMMENT '业务负责人(门户草稿)',
  `engine`           varchar(64)   DEFAULT NULL COMMENT 'Iceberg/Kafka/... 展示冗余',
  `is_gold`          tinyint(1)    NOT NULL DEFAULT 0 COMMENT '黄金资产标记(门户)',
  `grav_asset_id`    varchar(20)   DEFAULT NULL COMMENT 'cb_grav_asset_ref.id',
  `om_asset_id`      varchar(20)   DEFAULT NULL COMMENT 'cb_om_asset_ref.id',
  `om_fqn`           varchar(512)  DEFAULT NULL COMMENT 'OM FQN冗余便于检索',
  `last_sync_at`     datetime      DEFAULT NULL COMMENT '最近对齐时间',
  `last_sync_status` varchar(32)   DEFAULT 'never' COMMENT 'never/ok/error/skipped',
  `delete_flag`      varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`      datetime      DEFAULT NULL COMMENT '创建时间',
  `create_user`      varchar(20)   DEFAULT NULL COMMENT '创建用户',
  `update_time`      datetime      DEFAULT NULL COMMENT '修改时间',
  `update_user`      varchar(20)   DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_gov_asset_code` (`ws`,`asset_code`) USING BTREE,
  KEY `idx_gov_asset_layer` (`layer`) USING BTREE,
  KEY `idx_gov_asset_domain` (`domain_code`) USING BTREE,
  KEY `idx_gov_asset_kind` (`asset_kind`) USING BTREE,
  KEY `idx_gov_asset_status` (`status`) USING BTREE,
  KEY `idx_gov_asset_om_fqn` (`om_fqn`(191)) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='治理：门户资产登记';

CREATE TABLE IF NOT EXISTS `gov_asset_source_link` (
  `id`            varchar(20)   NOT NULL COMMENT '主键',
  `revision`      int           NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
  `status`        varchar(32)   DEFAULT 'active' COMMENT 'active/stale/detached',
  `ws`            varchar(64)   DEFAULT 'default' COMMENT '工作空间',
  `remark`        varchar(512)  DEFAULT NULL COMMENT '备注',
  `asset_id`      varchar(20)   NOT NULL COMMENT 'gov_asset.id',
  `ds_id`         varchar(20)   NOT NULL COMMENT 'ig_datasource.id',
  `ds_code`       varchar(64)   DEFAULT NULL COMMENT '冗余ds_code',
  `object_name`   varchar(512)  NOT NULL COMMENT '源端对象名=ig_ds_table.table_name',
  `object_kind`   varchar(32)   DEFAULT 'table' COMMENT '与清单objectKind对齐',
  `ds_table_id`   varchar(20)   DEFAULT NULL COMMENT 'ig_ds_table.id可选',
  `link_role`     varchar(32)   DEFAULT 'primary' COMMENT 'primary/upstream/reference',
  `delete_flag`   varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`   datetime      DEFAULT NULL COMMENT '创建时间',
  `create_user`   varchar(20)   DEFAULT NULL COMMENT '创建用户',
  `update_time`   datetime      DEFAULT NULL COMMENT '修改时间',
  `update_user`   varchar(20)   DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_gov_asset_src` (`asset_id`,`ds_id`,`object_name`) USING BTREE,
  UNIQUE KEY `uq_gov_src_object_primary` (`ds_id`,`object_name`,`link_role`) USING BTREE,
  KEY `idx_gov_link_asset` (`asset_id`) USING BTREE,
  KEY `idx_gov_link_ds` (`ds_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='治理：资产与数据源对象绑定';

-- cb_om_asset_ref：支持无 Grav 的 Messaging/Search/Storage 等实体
SET @db := DATABASE();

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@db AND TABLE_NAME='cb_om_asset_ref' AND COLUMN_NAME='asset_id');
SET @sql := IF(@c=0,
  'ALTER TABLE `cb_om_asset_ref` ADD COLUMN `asset_id` varchar(20) DEFAULT NULL COMMENT ''gov_asset.id'' AFTER `remark`',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@db AND TABLE_NAME='cb_om_asset_ref' AND COLUMN_NAME='om_entity_type');
SET @sql := IF(@c=0,
  'ALTER TABLE `cb_om_asset_ref` ADD COLUMN `om_entity_type` varchar(32) DEFAULT ''table'' COMMENT ''table/topic/searchIndex/container'' AFTER `asset_id`',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@db AND TABLE_NAME='cb_om_asset_ref' AND COLUMN_NAME='om_service_type');
SET @sql := IF(@c=0,
  'ALTER TABLE `cb_om_asset_ref` ADD COLUMN `om_service_type` varchar(64) DEFAULT NULL COMMENT ''Kafka/Mysql/ElasticSearch/S3...'' AFTER `om_entity_type`',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@db AND TABLE_NAME='cb_om_asset_ref' AND COLUMN_NAME='om_family');
SET @sql := IF(@c=0,
  'ALTER TABLE `cb_om_asset_ref` ADD COLUMN `om_family` varchar(32) DEFAULT ''DATABASE'' COMMENT ''DATABASE/MESSAGING/SEARCH/STORAGE/...'' AFTER `om_service_type`',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- grav_asset_id 改为可空（Kafka Topic 等无 Grav）
SET @nullable := (SELECT IS_NULLABLE FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@db AND TABLE_NAME='cb_om_asset_ref' AND COLUMN_NAME='grav_asset_id');
SET @sql := IF(@nullable='NO',
  'ALTER TABLE `cb_om_asset_ref` MODIFY COLUMN `grav_asset_id` varchar(20) DEFAULT NULL COMMENT ''cb_grav_asset_ref.id可空''',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=@db AND TABLE_NAME='cb_om_asset_ref' AND INDEX_NAME='idx_cb_om_asset');
SET @sql := IF(@idx=0,
  'ALTER TABLE `cb_om_asset_ref` ADD KEY `idx_cb_om_asset` (`asset_id`) USING BTREE',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
