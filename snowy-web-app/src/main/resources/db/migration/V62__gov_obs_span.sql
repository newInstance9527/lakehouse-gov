-- 链路监控 P0：门户旁路 span 表（可空；无 OTel 写入管道）

CREATE TABLE IF NOT EXISTS `gov_obs_span` (
  `id`              varchar(20)   NOT NULL COMMENT '主键',
  `ws`              varchar(64)   NOT NULL DEFAULT 'default' COMMENT '工作空间',
  `trace_id`        varchar(64)   NOT NULL COMMENT 'trace id',
  `span_id`         varchar(64)   NOT NULL COMMENT 'span id',
  `parent_span_id`  varchar(64)   DEFAULT NULL COMMENT '父 span',
  `link_id`         varchar(8)    DEFAULT NULL COMMENT 'A-L 链路',
  `service`         varchar(128)  DEFAULT NULL COMMENT '服务名',
  `op`              varchar(128)  DEFAULT NULL COMMENT '操作',
  `status`          varchar(32)   DEFAULT 'ok' COMMENT 'ok/warn/error/ghost',
  `start_ts`        datetime(3)   DEFAULT NULL COMMENT '开始',
  `end_ts`          datetime(3)   DEFAULT NULL COMMENT '结束',
  `duration_ms`     bigint        DEFAULT NULL COMMENT '耗时 ms',
  `run_id`          varchar(128)  DEFAULT NULL COMMENT '作业/查询 run',
  `event_id`        varchar(128)  DEFAULT NULL COMMENT '事件 id',
  `error`           varchar(1024) DEFAULT NULL COMMENT '错误摘要',
  `attrs_json`      mediumtext    COMMENT '扩展属性',
  `delete_flag`     varchar(32)   DEFAULT 'NOT_DELETE',
  `create_time`     datetime      DEFAULT NULL,
  `create_user`     varchar(20)   DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_obs_span_trace` (`trace_id`, `start_ts`) USING BTREE,
  KEY `idx_obs_span_link` (`ws`, `link_id`, `start_ts`) USING BTREE,
  KEY `idx_obs_span_run` (`run_id`) USING BTREE,
  KEY `idx_obs_span_event` (`event_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='可观测：门户旁路 span（§29）';
