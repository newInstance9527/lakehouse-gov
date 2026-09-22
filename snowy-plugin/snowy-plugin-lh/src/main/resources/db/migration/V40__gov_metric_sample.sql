-- 指标日波动采样（M2）：日点落库，供 /lh/metric/{code}/anomaly 与夜莺同源
-- 规格：doc/指标中心-引擎执行.md Phase M2 · doc/指标中心.md §6

CREATE TABLE IF NOT EXISTS `gov_metric_sample` (
  `id`             varchar(20)    NOT NULL COMMENT '主键',
  `ws`             varchar(64)    DEFAULT 'default' COMMENT '工作空间',
  `metric_code`    varchar(64)    NOT NULL COMMENT '指标编码',
  `ver`            varchar(16)    DEFAULT NULL COMMENT '采样时版本',
  `sample_dt`      date           NOT NULL COMMENT '业务日（通常昨日）',
  `metric_value`   decimal(32, 8) DEFAULT NULL COMMENT '采样标量',
  `prev_value`     decimal(32, 8) DEFAULT NULL COMMENT '前一日采样',
  `change_pct`     decimal(16, 6) DEFAULT NULL COMMENT '相对前日变动百分比',
  `anomaly`        tinyint        DEFAULT 0 COMMENT '1=超阈值',
  `threshold_pct`  decimal(16, 6) DEFAULT NULL COMMENT '判定阈值（%）',
  `status`         varchar(32)    DEFAULT 'ok' COMMENT 'ok/failed/degraded/skipped',
  `message`        varchar(512)   DEFAULT NULL COMMENT '失败/降级说明',
  `query_id`       varchar(64)    DEFAULT NULL COMMENT '关联 cp_query_exec.query_id',
  `create_time`    datetime       DEFAULT NULL COMMENT '采样时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_gov_metric_sample_dt` (`ws`, `metric_code`, `sample_dt`) USING BTREE,
  KEY `idx_gov_metric_sample_code` (`metric_code`) USING BTREE,
  KEY `idx_gov_metric_sample_anomaly` (`anomaly`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='指标日波动采样';
