-- 数据标准：标准字段 / 码值 / 命名 / 映射 / 落地检测结果

CREATE TABLE IF NOT EXISTS `gov_std_field` (
  `id`                varchar(20)   NOT NULL COMMENT '主键',
  `revision`          int           NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
  `status`            varchar(32)   NOT NULL DEFAULT 'active' COMMENT 'active/deprecated',
  `ws`                varchar(64)   DEFAULT 'default' COMMENT '工作空间',
  `remark`            varchar(512)  DEFAULT NULL COMMENT '备注',
  `field_name`        varchar(128)  NOT NULL COMMENT '标准字段名',
  `data_type`         varchar(64)   DEFAULT NULL COMMENT '展示类型 DECIMAL(18,2) 等',
  `unit`              varchar(32)   DEFAULT NULL COMMENT '元/分/码值/—',
  `description`       varchar(1024) DEFAULT NULL COMMENT '业务含义',
  `domain_code`       varchar(64)   DEFAULT NULL COMMENT '业务域编码',
  `compliance_status` varchar(16)   DEFAULT 'ok' COMMENT 'ok/warn/fail 聚合或标记',
  `om_glossary_fqn`   varchar(512)  DEFAULT NULL COMMENT '可选 OM Glossary Term FQN',
  `delete_flag`       varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`       datetime      DEFAULT NULL COMMENT '创建时间',
  `create_user`       varchar(20)   DEFAULT NULL COMMENT '创建用户',
  `update_time`       datetime      DEFAULT NULL COMMENT '修改时间',
  `update_user`       varchar(20)   DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_gov_std_field` (`ws`,`field_name`) USING BTREE,
  KEY `idx_gov_std_field_domain` (`domain_code`) USING BTREE,
  KEY `idx_gov_std_field_status` (`compliance_status`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='治理：标准字段';

CREATE TABLE IF NOT EXISTS `gov_std_code` (
  `id`                varchar(20)   NOT NULL COMMENT '主键',
  `revision`          int           NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
  `status`            varchar(32)   NOT NULL DEFAULT 'active' COMMENT 'active/deprecated',
  `ws`                varchar(64)   DEFAULT 'default' COMMENT '工作空间',
  `remark`            varchar(512)  DEFAULT NULL COMMENT '备注',
  `code_set_id`       varchar(64)   NOT NULL COMMENT '对外码值集ID如 STD-C0021',
  `name`              varchar(128)  NOT NULL COMMENT '码值集名称',
  `field_name`        varchar(128)  NOT NULL COMMENT '绑定标准字段名',
  `mapped_summary`    varchar(512)  DEFAULT NULL COMMENT '已映射表摘要冗余',
  `compliance_status` varchar(16)   DEFAULT 'ok' COMMENT 'ok/warn/fail',
  `om_glossary_fqn`   varchar(512)  DEFAULT NULL COMMENT '可选 OM 术语 FQN',
  `delete_flag`       varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`       datetime      DEFAULT NULL COMMENT '创建时间',
  `create_user`       varchar(20)   DEFAULT NULL COMMENT '创建用户',
  `update_time`       datetime      DEFAULT NULL COMMENT '修改时间',
  `update_user`       varchar(20)   DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_gov_std_code` (`ws`,`code_set_id`) USING BTREE,
  KEY `idx_gov_std_code_field` (`field_name`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='治理：标准码值头';

CREATE TABLE IF NOT EXISTS `gov_std_code_item` (
  `id`            varchar(20)   NOT NULL COMMENT '主键',
  `revision`      int           NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
  `status`        varchar(32)   DEFAULT 'active' COMMENT 'active/deprecated',
  `ws`            varchar(64)   DEFAULT 'default' COMMENT '工作空间',
  `remark`        varchar(512)  DEFAULT NULL COMMENT '备注',
  `code_id`       varchar(20)   NOT NULL COMMENT 'gov_std_code.id',
  `item_code`     varchar(64)   NOT NULL COMMENT '枚举编码',
  `item_label`    varchar(256)  NOT NULL COMMENT '枚举含义',
  `sort_no`       int           DEFAULT 0 COMMENT '展示序',
  `delete_flag`   varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`   datetime      DEFAULT NULL COMMENT '创建时间',
  `create_user`   varchar(20)   DEFAULT NULL COMMENT '创建用户',
  `update_time`   datetime      DEFAULT NULL COMMENT '修改时间',
  `update_user`   varchar(20)   DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_gov_std_code_item` (`code_id`,`item_code`) USING BTREE,
  KEY `idx_gov_std_code_item_code` (`code_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='治理：标准码值枚举行';

CREATE TABLE IF NOT EXISTS `gov_std_naming` (
  `id`            varchar(20)   NOT NULL COMMENT '主键',
  `revision`      int           NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
  `status`        varchar(32)   NOT NULL DEFAULT 'ok' COMMENT 'ok/warn/fail',
  `ws`            varchar(64)   DEFAULT 'default' COMMENT '工作空间',
  `remark`        varchar(512)  DEFAULT NULL COMMENT '备注',
  `pattern`       varchar(256)  NOT NULL COMMENT '命名模板',
  `example`       varchar(256)  DEFAULT NULL COMMENT '示例',
  `layer`         varchar(32)   NOT NULL COMMENT 'ODS/DWD/.../任务/消息等',
  `delete_flag`   varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`   datetime      DEFAULT NULL COMMENT '创建时间',
  `create_user`   varchar(20)   DEFAULT NULL COMMENT '创建用户',
  `update_time`   datetime      DEFAULT NULL COMMENT '修改时间',
  `update_user`   varchar(20)   DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_gov_std_naming` (`ws`,`layer`,`pattern`) USING BTREE,
  KEY `idx_gov_std_naming_layer` (`layer`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='治理：命名规范';

CREATE TABLE IF NOT EXISTS `gov_std_mapping` (
  `id`             varchar(20)   NOT NULL COMMENT '主键',
  `revision`       int           NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
  `status`         varchar(32)   NOT NULL DEFAULT 'ok' COMMENT 'ok/warn/fail',
  `ws`             varchar(64)   DEFAULT 'default' COMMENT '工作空间',
  `remark`         varchar(512)  DEFAULT NULL COMMENT '备注',
  `src_object`     varchar(256)  NOT NULL COMMENT '源对象名如 s_order',
  `src_field`      varchar(128)  NOT NULL COMMENT '源字段名',
  `std_field_name` varchar(128)  NOT NULL COMMENT '标准字段名',
  `code_set_id`    varchar(64)   DEFAULT NULL COMMENT '关联码值集可选',
  `target_table`   varchar(256)  NOT NULL DEFAULT '' COMMENT '目标表或资产编码',
  `rule_text`      varchar(1024) DEFAULT NULL COMMENT '映射规则说明/CASE',
  `ds_id`          varchar(20)   DEFAULT NULL COMMENT 'ig_datasource.id',
  `asset_id`       varchar(20)   DEFAULT NULL COMMENT 'gov_asset.id 可选',
  `etl_job_id`     varchar(64)   DEFAULT NULL COMMENT '来源 ETL 作业',
  `delete_flag`    varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`    datetime      DEFAULT NULL COMMENT '创建时间',
  `create_user`    varchar(20)   DEFAULT NULL COMMENT '创建用户',
  `update_time`    datetime      DEFAULT NULL COMMENT '修改时间',
  `update_user`    varchar(20)   DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  -- utf8mb4 下全列唯一键会超 3072；长列用前缀
  UNIQUE KEY `uq_gov_std_mapping` (`ws`,`src_object`(128),`src_field`,`target_table`(128),`std_field_name`) USING BTREE,
  KEY `idx_gov_std_mapping_std` (`std_field_name`) USING BTREE,
  KEY `idx_gov_std_mapping_ds` (`ds_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='治理：源到标准映射';

CREATE TABLE IF NOT EXISTS `gov_std_detect_result` (
  `id`            varchar(20)   NOT NULL COMMENT '主键',
  `ws`            varchar(64)   DEFAULT 'default' COMMENT '工作空间',
  `table_name`    varchar(256)  NOT NULL COMMENT '落地表名',
  `field_name`    varchar(128)  NOT NULL COMMENT '字段名',
  `std_ref`       varchar(128)  NOT NULL COMMENT '标准字段名或码值集ID',
  `check_type`    varchar(64)   NOT NULL COMMENT '码值合规/单位+类型/脱敏等',
  `result_text`   varchar(1024) DEFAULT NULL COMMENT '结果摘要',
  `status`        varchar(16)   NOT NULL COMMENT 'ok/warn/fail',
  `asset_id`      varchar(20)   DEFAULT NULL COMMENT 'gov_asset.id',
  `checked_at`    datetime      NOT NULL COMMENT '检测时间',
  `run_id`        varchar(64)   DEFAULT NULL COMMENT '检测作业 run',
  `create_time`   datetime      DEFAULT NULL COMMENT '写入时间',
  `create_user`   varchar(20)   DEFAULT NULL COMMENT '写入用户/系统',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_gov_std_detect_table` (`table_name`,`field_name`) USING BTREE,
  KEY `idx_gov_std_detect_status` (`status`) USING BTREE,
  KEY `idx_gov_std_detect_checked` (`checked_at`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='治理：标准落地检测结果(流水)';
