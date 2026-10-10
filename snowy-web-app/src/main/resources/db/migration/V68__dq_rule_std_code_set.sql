-- 质量枚举规则绑定数据标准码值集（gov_std_code.code_set_id）
ALTER TABLE `gov_dq_rule`
  ADD COLUMN `std_code_set_id` varchar(128) DEFAULT NULL COMMENT '绑定 gov_std_code.code_set_id（枚举/码值规则）' AFTER `om_test_fqn`;

CREATE INDEX `idx_gov_dq_std_code` ON `gov_dq_rule` (`std_code_set_id`);
