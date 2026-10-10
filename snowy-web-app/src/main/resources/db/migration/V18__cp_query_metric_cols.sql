-- 指标执行审计扩展：source / metric_code / metric_ver

ALTER TABLE `cp_query_exec`
  ADD COLUMN `source` varchar(32) DEFAULT 'adhoc' COMMENT 'adhoc/metric/metric_trial' AFTER `engine`,
  ADD COLUMN `metric_code` varchar(64) DEFAULT NULL COMMENT '指标编码' AFTER `source`,
  ADD COLUMN `metric_ver` varchar(16) DEFAULT NULL COMMENT '指标版本' AFTER `metric_code`;

ALTER TABLE `cp_query_exec`
  ADD KEY `idx_cp_query_exec_metric` (`metric_code`, `create_time`);
