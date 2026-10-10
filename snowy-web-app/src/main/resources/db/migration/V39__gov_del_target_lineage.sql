-- E1：合规删除计划项挂血缘展开元数据（assess ← lineage.expand / impact）
-- inferred 边须人工确认后方可提交审批

ALTER TABLE `gov_del_target`
  ADD COLUMN `lineage_confidence` varchar(16)  DEFAULT NULL COMMENT 'explicit/inferred；空=主体索引直出' AFTER `manual_added`,
  ADD COLUMN `lineage_hop`        int          DEFAULT NULL COMMENT '相对焦点表的下游跳数' AFTER `lineage_confidence`,
  ADD COLUMN `lineage_layer`      varchar(16)  DEFAULT NULL COMMENT 'ODS/DWD/ADS/表' AFTER `lineage_hop`,
  ADD COLUMN `owner`              varchar(64)  DEFAULT NULL COMMENT '资产/作业 Owner' AFTER `lineage_layer`,
  ADD COLUMN `sensitivity`        varchar(16)  DEFAULT NULL COMMENT '公开/内部/秘密/机密' AFTER `owner`,
  ADD COLUMN `has_subject_col`    tinyint(1)   DEFAULT NULL COMMENT '是否有主体列（命中 subject_map 或 id_column）' AFTER `sensitivity`,
  ADD COLUMN `lineage_confirmed`  tinyint(1)   NOT NULL DEFAULT 0 COMMENT 'inferred 人工确认' AFTER `has_subject_col`;
