-- AI 用量硬门禁 P0：工作空间日 Token / 成本上限
-- NULL 或 0 = 不限；与 cu_quota（日上限）同周期风格

ALTER TABLE `gov_ws_quota`
  ADD COLUMN `ai_token_quota` bigint        DEFAULT NULL COMMENT 'AI Token 日上限；NULL/0=不限' AFTER `api_qps_used`,
  ADD COLUMN `ai_cost_quota`  decimal(18,4) DEFAULT NULL COMMENT 'AI 成本日上限（与 usage 币种一致）；NULL/0=不限' AFTER `ai_token_quota`;
