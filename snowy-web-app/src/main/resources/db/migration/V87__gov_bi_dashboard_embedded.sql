-- BI：Superset Embedded UUID（Guest Token 内嵌看数）

ALTER TABLE `gov_bi_dashboard`
  ADD COLUMN `embedded_uuid` varchar(64) DEFAULT NULL COMMENT 'Superset embedded dashboard uuid' AFTER `url_path`;
