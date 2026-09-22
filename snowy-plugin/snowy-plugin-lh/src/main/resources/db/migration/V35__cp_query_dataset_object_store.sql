-- 即席数据集抽样：元数据留门户库，正文落对象存储；sample_json 仅作降级兜底

ALTER TABLE `cp_query_dataset`
  ADD COLUMN `sample_bucket`     varchar(128)  DEFAULT NULL COMMENT '抽样对象桶' AFTER `sample_json`,
  ADD COLUMN `sample_object_key` varchar(512)  DEFAULT NULL COMMENT '抽样对象 key' AFTER `sample_bucket`,
  ADD COLUMN `sample_uri`        varchar(768)  DEFAULT NULL COMMENT '抽样对象 URI（s3a://bucket/key）' AFTER `sample_object_key`,
  ADD COLUMN `sample_sha256`     varchar(64)   DEFAULT NULL COMMENT '抽样 JSON sha256' AFTER `sample_uri`,
  ADD COLUMN `sample_storage`    varchar(16)   DEFAULT NULL COMMENT 'object/db' AFTER `sample_sha256`;
