-- 指标治理瘦身：删除主题 / 数据管家 / 标准字段挂接（不再作为指标属性维护）
-- 保留 value_type / value_precision / tags_json；域树 parent_code 仍服务业务域分层

ALTER TABLE `gov_metric`
  DROP COLUMN `theme_code`,
  DROP COLUMN `steward`,
  DROP COLUMN `std_field_id`;
