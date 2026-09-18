-- 平台发件箱：跨系统事件（datasource.changed 等）
CREATE TABLE IF NOT EXISTS `plat_outbox_event` (
  `id`             varchar(20)  NOT NULL COMMENT '主键',
  `event_id`       varchar(64)  NOT NULL COMMENT '幂等事件ID',
  `event_type`     varchar(128) NOT NULL COMMENT '事件类型',
  `aggregate_type` varchar(64)  NOT NULL COMMENT '聚合类型',
  `aggregate_id`   varchar(64)  NOT NULL COMMENT '聚合ID',
  `payload`        json         NOT NULL COMMENT '载荷',
  `headers`        json         DEFAULT NULL COMMENT '头',
  `status`         varchar(32)  NOT NULL DEFAULT 'pending' COMMENT 'pending/sent/failed/dead',
  `retry_count`    int          NOT NULL DEFAULT 0 COMMENT '重试次数',
  `next_retry_at`  datetime     DEFAULT NULL COMMENT '下次重试时间',
  `trace_id`       varchar(64)  DEFAULT NULL COMMENT '链路ID',
  `sent_at`        datetime     DEFAULT NULL COMMENT '发送时间',
  `last_error`     varchar(512) DEFAULT NULL COMMENT '最近错误',
  `create_time`    datetime     DEFAULT NULL COMMENT '创建时间',
  `create_user`    varchar(20)  DEFAULT NULL COMMENT '创建用户',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_plat_outbox_event_id` (`event_id`) USING BTREE,
  KEY `idx_plat_outbox_status` (`status`,`next_retry_at`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='流水：跨系统发件箱';
