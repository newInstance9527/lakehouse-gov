-- 数据契约 P0：门户 Schema / 版本 / 变更单 / CDC 配置（不依赖外置 Registry）

CREATE TABLE IF NOT EXISTS `gov_contract_schema` (
  `id`              varchar(20)   NOT NULL COMMENT '主键',
  `revision`        int           DEFAULT 1 COMMENT '乐观锁',
  `status`          varchar(32)   DEFAULT 'ok' COMMENT 'ok/warn/fail',
  `ws`              varchar(64)   NOT NULL DEFAULT 'default' COMMENT '工作空间',
  `remark`          varchar(512)  DEFAULT NULL COMMENT '备注',
  `name`            varchar(256)  NOT NULL COMMENT 'Topic/表名',
  `kind`            varchar(32)   NOT NULL DEFAULT 'topic' COMMENT 'topic/table',
  `schema_type`     varchar(64)   DEFAULT 'AVRO' COMMENT 'AVRO/JSON/PROTO 等',
  `compat`          varchar(32)   DEFAULT 'BACKWARD' COMMENT '兼容级别',
  `fields_json`     mediumtext    COMMENT '字段定义 JSON 或原文',
  `field_count`     int           DEFAULT 0 COMMENT '字段数',
  `current_version` varchar(32)   DEFAULT 'v1' COMMENT '当前版本号',
  `last_change`     varchar(512)  DEFAULT NULL COMMENT '最近变更摘要',
  `delete_flag`     varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`     datetime      DEFAULT NULL,
  `create_user`     varchar(20)   DEFAULT NULL,
  `update_time`     datetime      DEFAULT NULL,
  `update_user`     varchar(20)   DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_contract_schema_ws_name` (`ws`, `name`) USING BTREE,
  KEY `idx_contract_schema_ws` (`ws`, `status`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='契约：Schema 注册';

CREATE TABLE IF NOT EXISTS `gov_contract_schema_ver` (
  `id`              varchar(20)   NOT NULL COMMENT '主键',
  `schema_id`       varchar(20)   NOT NULL COMMENT 'gov_contract_schema.id',
  `ws`              varchar(64)   NOT NULL DEFAULT 'default',
  `version`         varchar(32)   NOT NULL COMMENT '版本号',
  `compat`          varchar(32)   DEFAULT NULL,
  `fields_json`     mediumtext    COMMENT '该版本字段',
  `diff_summary`    varchar(1024) DEFAULT NULL COMMENT '相对上一版 diff',
  `delete_flag`     varchar(32)   DEFAULT 'NOT_DELETE',
  `create_time`     datetime      DEFAULT NULL,
  `create_user`     varchar(20)   DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_contract_ver_schema` (`schema_id`, `create_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='契约：Schema 版本历史';

CREATE TABLE IF NOT EXISTS `gov_contract_change` (
  `id`              varchar(20)   NOT NULL COMMENT '主键',
  `revision`        int           DEFAULT 1,
  `status`          varchar(32)   NOT NULL DEFAULT 'draft' COMMENT 'draft/checking/review/approved/rejected/executed/blocked',
  `ws`              varchar(64)   NOT NULL DEFAULT 'default',
  `remark`          varchar(1024) DEFAULT NULL,
  `schema_id`       varchar(20)   DEFAULT NULL,
  `schema_name`     varchar(256)  NOT NULL COMMENT '目标 Schema 名',
  `title`           varchar(256)  DEFAULT NULL,
  `change_summary`  varchar(1024) DEFAULT NULL,
  `compat_result`   varchar(32)   DEFAULT NULL COMMENT 'ok/warn/fail',
  `impact_json`     mediumtext    COMMENT '血缘影响摘要',
  `fields_json`     mediumtext    COMMENT '拟变更字段',
  `delete_flag`     varchar(32)   DEFAULT 'NOT_DELETE',
  `create_time`     datetime      DEFAULT NULL,
  `create_user`     varchar(20)   DEFAULT NULL,
  `update_time`     datetime      DEFAULT NULL,
  `update_user`     varchar(20)   DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_contract_change_ws_st` (`ws`, `status`, `create_time`) USING BTREE,
  KEY `idx_contract_change_schema` (`schema_name`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='契约：变更单';

CREATE TABLE IF NOT EXISTS `gov_contract_cdc_cfg` (
  `id`              varchar(20)   NOT NULL COMMENT '主键',
  `ws`              varchar(64)   NOT NULL DEFAULT 'default',
  `topic`           varchar(256)  NOT NULL COMMENT 'CDC topic',
  `config_json`     mediumtext    COMMENT '6 项语义 JSON',
  `delete_flag`     varchar(32)   DEFAULT 'NOT_DELETE',
  `create_time`     datetime      DEFAULT NULL,
  `create_user`     varchar(20)   DEFAULT NULL,
  `update_time`     datetime      DEFAULT NULL,
  `update_user`     varchar(20)   DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_contract_cdc_ws_topic` (`ws`, `topic`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='契约：CDC 语义配置';
