-- 指标公共维度挂接数仓 DIM 层表（成员权威在湖；无演示种子）

ALTER TABLE `gov_metric_dim`
  ADD COLUMN `dim_table` varchar(256) DEFAULT NULL COMMENT 'DIM 层表 FQN，如 iceberg.dim.dim_region' AFTER `biz_process`,
  ADD COLUMN `member_code_col` varchar(128) DEFAULT NULL COMMENT '成员编码列' AFTER `dim_table`,
  ADD COLUMN `member_name_col` varchar(128) DEFAULT NULL COMMENT '成员名称列' AFTER `member_code_col`,
  ADD COLUMN `parent_code_col` varchar(128) DEFAULT NULL COMMENT '父编码列（层级预留）' AFTER `member_name_col`,
  ADD COLUMN `dim_status` varchar(32) DEFAULT 'unbound' COMMENT 'unbound/bound' AFTER `parent_code_col`;
