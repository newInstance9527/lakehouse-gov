-- 存储趋势日报定时订阅（对齐存储趋势 §2.9 / §32.4）
CREATE TABLE IF NOT EXISTS `gov_lc_storage_report_sub` (
  `id`            varchar(64)  NOT NULL COMMENT '主键',
  `revision`      int          NOT NULL DEFAULT 1,
  `ws`            varchar(64)  DEFAULT NULL COMMENT '空间过滤；空=全局',
  `range_key`     varchar(16)  NOT NULL DEFAULT '30d' COMMENT '7d/30d/90d',
  `format`        varchar(16)  NOT NULL DEFAULT 'md' COMMENT 'md/csv',
  `webhook_url`   varchar(512) NOT NULL COMMENT '推送 URL（POST text/plain 或 json）',
  `cron_expr`     varchar(64)  NOT NULL DEFAULT '0 0 8 * * ?' COMMENT 'Quartz cron；门户调度用固定日批+过滤',
  `enabled`       tinyint      NOT NULL DEFAULT 1,
  `last_status`   varchar(32)  DEFAULT NULL COMMENT 'ok/failed/skipped',
  `last_message`  varchar(512) DEFAULT NULL,
  `last_run_time` datetime     DEFAULT NULL,
  `create_user`   varchar(64)  DEFAULT NULL,
  `create_time`   datetime     DEFAULT NULL,
  `update_user`   varchar(64)  DEFAULT NULL,
  `update_time`   datetime     DEFAULT NULL,
  `delete_flag`   varchar(16)  NOT NULL DEFAULT 'NOT_DELETE',
  PRIMARY KEY (`id`),
  KEY `idx_lc_report_sub_en` (`enabled`, `delete_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='存储日报订阅';
