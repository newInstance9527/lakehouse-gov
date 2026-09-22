-- dataapi_api_key_meta：订阅配额与 AppKey 展示

ALTER TABLE `dataapi_api_key_meta`
  ADD COLUMN `qps_limit` int DEFAULT 100 COMMENT '订阅方 QPS 配额' AFTER `expire_at`,
  ADD COLUMN `app_key` varchar(64) DEFAULT NULL COMMENT '对外 AppKey（非密文）' AFTER `qps_limit`;
