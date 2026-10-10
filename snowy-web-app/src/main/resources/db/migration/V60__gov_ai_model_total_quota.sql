-- 模型配额改为终身/总限额（非日限额）
-- daily_* → token_quota / cost_quota；NULL/0=不限

ALTER TABLE `gov_ai_model`
  CHANGE COLUMN `daily_token_quota` `token_quota` bigint        DEFAULT NULL COMMENT '模型 Token 总限额；NULL/0=不限',
  CHANGE COLUMN `daily_cost_quota`  `cost_quota`  decimal(18,4) DEFAULT NULL COMMENT '模型成本总限额；NULL/0=不限';
