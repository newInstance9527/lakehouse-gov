-- AI 日用量：累计响应时延，供 overview「平均响应」加权均值
ALTER TABLE `gov_ai_usage_daily`
  ADD COLUMN `latency_sum_ms` bigint NOT NULL DEFAULT 0 COMMENT '当日累计响应毫秒（各次调用之和）' AFTER `completion_tokens`,
  ADD COLUMN `latency_samples` bigint NOT NULL DEFAULT 0 COMMENT '计入时延的调用次数' AFTER `latency_sum_ms`;
