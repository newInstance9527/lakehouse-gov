-- 即席执行审计：脱敏来源可观测（与 mask_cols 分离）
ALTER TABLE `cp_query_exec`
  ADD COLUMN `mask_source` varchar(64) DEFAULT NULL COMMENT '脱敏策略来源：engine/policy_proj/grant/grav/none' AFTER `mask_cols`,
  ADD COLUMN `mask_degraded` tinyint(1) DEFAULT NULL COMMENT '1=无策略降级（未假装脱敏）' AFTER `mask_source`;
