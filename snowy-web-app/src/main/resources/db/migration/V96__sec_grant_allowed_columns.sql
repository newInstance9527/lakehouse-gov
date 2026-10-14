-- 表读授权列白名单：空=不限制列；与 column_mask（脱敏摘要）分离
ALTER TABLE `sec_auth_grant`
  ADD COLUMN `allowed_columns` varchar(2048) DEFAULT NULL COMMENT '逗号分隔列白名单；空=不限制列' AFTER `column_mask`;
