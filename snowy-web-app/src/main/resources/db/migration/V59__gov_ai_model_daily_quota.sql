-- AI 模型日配额硬门禁：单模型 Token / 成本上限（与 ws 日周期一致）
-- NULL 或 0 = 该模型不限

ALTER TABLE `gov_ai_model`
  ADD COLUMN `daily_token_quota` bigint        DEFAULT NULL COMMENT '模型 Token 日上限；NULL/0=不限' AFTER `cost_total`,
  ADD COLUMN `daily_cost_quota`  decimal(18,4) DEFAULT NULL COMMENT '模型成本日上限；NULL/0=不限' AFTER `daily_token_quota`;
