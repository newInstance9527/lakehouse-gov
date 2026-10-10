-- 指标 M4：主题域树、治理元数据、标签、标准挂接；维度挂事实表（无演示种子）

ALTER TABLE `gov_domain`
  ADD COLUMN `parent_code` varchar(64) DEFAULT NULL COMMENT '父域/主题域编码' AFTER `domain_code`;

ALTER TABLE `gov_metric`
  ADD COLUMN `theme_code` varchar(64) DEFAULT NULL COMMENT '主题（可指向域树节点）' AFTER `domain_code`,
  ADD COLUMN `steward` varchar(64) DEFAULT NULL COMMENT '数据管家' AFTER `owner`,
  ADD COLUMN `value_type` varchar(32) DEFAULT NULL COMMENT '值类型 number/ratio/currency/count…' AFTER `unit`,
  ADD COLUMN `value_precision` int DEFAULT NULL COMMENT '小数精度' AFTER `value_type`,
  ADD COLUMN `tags_json` text COMMENT '标签 JSON 数组' AFTER `value_precision`,
  ADD COLUMN `std_field_id` varchar(20) DEFAULT NULL COMMENT '关联标准字段 gov_std_field.id' AFTER `tags_json`;

ALTER TABLE `gov_metric_dim`
  ADD COLUMN `fact_table` varchar(256) DEFAULT NULL COMMENT '关联事实/宽表（弱建模）' AFTER `grain_role`,
  ADD COLUMN `biz_process` varchar(256) DEFAULT NULL COMMENT '业务过程说明' AFTER `fact_table`;
