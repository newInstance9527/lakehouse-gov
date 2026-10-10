-- 可靠性 §33：对账规则 + 差异下钻（空列表合法，无演示种子）

CREATE TABLE IF NOT EXISTS `recon_rule` (
  `id`            varchar(20)   NOT NULL COMMENT '主键',
  `ws`            varchar(64)   NOT NULL DEFAULT 'default' COMMENT '工作空间',
  `rule_code`     varchar(64)   NOT NULL COMMENT '规则编码',
  `rule_name`     varchar(128)  DEFAULT NULL COMMENT '名称',
  `rule_type`     varchar(32)   NOT NULL COMMENT 'row_count|pk_hash|amount|partition|type_check',
  `lake_table`    varchar(256)  DEFAULT NULL COMMENT '湖表 FQN',
  `ck_database`   varchar(128)  DEFAULT NULL COMMENT 'CK 库',
  `ck_table`      varchar(256)  DEFAULT NULL COMMENT 'CK 表',
  `metric_code`   varchar(64)   DEFAULT NULL COMMENT '关联指标',
  `threshold`     decimal(18,8) NOT NULL DEFAULT 0.001 COMMENT '阈值',
  `enabled`       tinyint(1)    NOT NULL DEFAULT 1 COMMENT '1启用',
  `cron`          varchar(64)   DEFAULT NULL COMMENT '调度表达式可选',
  `extra_json`    mediumtext    COMMENT '扩展',
  `last_status`   varchar(16)   DEFAULT NULL COMMENT '最近结果 pass/fail/null',
  `last_checked`  datetime      DEFAULT NULL COMMENT '最近检测',
  `delete_flag`   varchar(32)   DEFAULT 'NOT_DELETE',
  `create_time`   datetime      DEFAULT NULL,
  `create_user`   varchar(20)   DEFAULT NULL,
  `update_time`   datetime      DEFAULT NULL,
  `update_user`   varchar(20)   DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_recon_rule_code` (`ws`,`rule_code`) USING BTREE,
  KEY `idx_recon_rule_type` (`rule_type`,`enabled`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='对账规则（§33.1）';

CREATE TABLE IF NOT EXISTS `recon_diff` (
  `id`            varchar(20)   NOT NULL COMMENT '主键',
  `ws`            varchar(64)   NOT NULL DEFAULT 'default' COMMENT '工作空间',
  `rule_id`       varchar(20)   DEFAULT NULL COMMENT '规则 id',
  `metric_code`   varchar(64)   DEFAULT NULL COMMENT '指标',
  `lake_table`    varchar(256)  DEFAULT NULL COMMENT '湖表',
  `partition_key` varchar(128)  DEFAULT NULL COMMENT '分区 dt=…',
  `diff_type`     varchar(32)   NOT NULL COMMENT 'missing|extra|pk|amount|type|partition',
  `pk_value`      varchar(256)  DEFAULT NULL COMMENT '主键值',
  `detail_json`   mediumtext    COMMENT '差异详情',
  `checked_at`    datetime      NOT NULL COMMENT '检测时间',
  `create_time`   datetime      DEFAULT NULL,
  `create_user`   varchar(20)   DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_recon_diff_table` (`lake_table`,`partition_key`,`checked_at`) USING BTREE,
  KEY `idx_recon_diff_rule` (`rule_id`,`checked_at`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='对账差异下钻（§33.2）';

CREATE TABLE IF NOT EXISTS `recon_golden_event` (
  `id`            varchar(20)   NOT NULL COMMENT '主键',
  `ws`            varchar(64)   NOT NULL DEFAULT 'default',
  `lake_table`    varchar(256)  NOT NULL COMMENT '表',
  `metric_code`   varchar(64)   DEFAULT NULL,
  `action`        varchar(32)   NOT NULL COMMENT 'delist|restore|rewrite_ck',
  `status`        varchar(32)   NOT NULL DEFAULT 'done' COMMENT 'pending|done|failed',
  `note`          varchar(512)  DEFAULT NULL,
  `trace_id`      varchar(64)   DEFAULT NULL,
  `create_time`   datetime      DEFAULT NULL,
  `create_user`   varchar(20)   DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_recon_golden_table` (`lake_table`,`create_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='黄金摘牌/重导留痕（§33.3）';
