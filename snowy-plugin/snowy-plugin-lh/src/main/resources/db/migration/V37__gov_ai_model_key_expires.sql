-- D1：模型 Key 过期预警（巡检读 key_expires_at → status=warn）
-- 规格：doc/AI模型管理.md §3.3 · doc/AI平台能力-部署说明.md

ALTER TABLE `gov_ai_model`
  ADD COLUMN `key_expires_at` datetime DEFAULT NULL COMMENT 'Key 过期时间（人工维护；巡检预警）' AFTER `key_mask`;
