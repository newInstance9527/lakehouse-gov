-- 指标修饰词 / 公共维度主数据（无演示种子；空库走空态维护）
-- 规格：指标能力补齐计划 M3 B-P1

CREATE TABLE IF NOT EXISTS `gov_metric_qualifier` (
  `id`             varchar(20)  NOT NULL COMMENT '主键',
  `revision`       int          NOT NULL DEFAULT 1,
  `status`         varchar(32)  DEFAULT 'active' COMMENT 'active/disabled',
  `ws`             varchar(64)  DEFAULT 'default',
  `remark`         varchar(512) DEFAULT NULL,
  `code`           varchar(64)  NOT NULL COMMENT '修饰词编码',
  `name`           varchar(128) NOT NULL COMMENT '显示名',
  `domain_code`    varchar(64)  DEFAULT NULL COMMENT '适用业务域，空=通用',
  `table_pattern`  varchar(128) DEFAULT NULL COMMENT '适用表名模式（可选，如 %order%）',
  `expr_dsl`       varchar(512) NOT NULL COMMENT '白名单谓词 DSL',
  `expr_sql`       varchar(512) NOT NULL COMMENT '编译后 SQL 片段（写入 ver 快照用）',
  `delete_flag`    varchar(32)  DEFAULT 'NOT_DELETE',
  `create_time`    datetime     DEFAULT NULL,
  `create_user`    varchar(20)  DEFAULT NULL,
  `update_time`    datetime     DEFAULT NULL,
  `update_user`    varchar(20)  DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_gov_metric_qualifier_code` (`ws`, `code`) USING BTREE,
  KEY `idx_gov_metric_qualifier_domain` (`domain_code`) USING BTREE,
  KEY `idx_gov_metric_qualifier_status` (`status`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='指标修饰词主数据';

CREATE TABLE IF NOT EXISTS `gov_metric_dim` (
  `id`             varchar(20)  NOT NULL COMMENT '主键',
  `revision`       int          NOT NULL DEFAULT 1,
  `status`         varchar(32)  DEFAULT 'active' COMMENT 'active/disabled',
  `ws`             varchar(64)  DEFAULT 'default',
  `remark`         varchar(512) DEFAULT NULL,
  `code`           varchar(64)  NOT NULL COMMENT '维度编码',
  `name`           varchar(128) NOT NULL COMMENT '显示名',
  `domain_code`    varchar(64)  DEFAULT NULL COMMENT '适用业务域，空=通用',
  `column_name`    varchar(128) NOT NULL COMMENT '湖表列名（grain 快照）',
  `grain_role`     varchar(32)  DEFAULT 'biz' COMMENT 'time/biz',
  `delete_flag`    varchar(32)  DEFAULT 'NOT_DELETE',
  `create_time`    datetime     DEFAULT NULL,
  `create_user`    varchar(20)  DEFAULT NULL,
  `update_time`    datetime     DEFAULT NULL,
  `update_user`    varchar(20)  DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_gov_metric_dim_code` (`ws`, `code`) USING BTREE,
  KEY `idx_gov_metric_dim_domain` (`domain_code`) USING BTREE,
  KEY `idx_gov_metric_dim_col` (`column_name`) USING BTREE,
  KEY `idx_gov_metric_dim_status` (`status`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='指标公共维度主数据';
