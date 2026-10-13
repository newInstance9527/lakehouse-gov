-- 业务术语类型化边（从 gov_kb_entry.refs_json 物化；KB term = Business Glossary SoT）
-- 规格：doc/知识库.md · 命名与工程约束 §6.2 旁路登记

CREATE TABLE IF NOT EXISTS `gov_kb_term_edge` (
  `id`              varchar(20)   NOT NULL COMMENT '主键',
  `revision`        int           NOT NULL DEFAULT 1 COMMENT '乐观锁',
  `status`          varchar(32)   NOT NULL DEFAULT 'active' COMMENT 'active/broken',
  `ws`              varchar(64)   DEFAULT 'default' COMMENT '工作空间痕迹',
  `remark`          varchar(512)  DEFAULT NULL COMMENT '备注',
  `from_entry_id`   varchar(20)   NOT NULL COMMENT '源术语条目 gov_kb_entry.id',
  `edge_type`       varchar(32)   NOT NULL COMMENT 'synonym/broader/related/implements_metric/maps_column/governed_by_std',
  `to_kind`         varchar(32)   NOT NULL COMMENT 'term/metric/asset/standard/label',
  `to_id`           varchar(256)  NOT NULL COMMENT '目标标识（entryId/metric_code/FQN/标准ID/同义词文本）',
  `to_label`        varchar(256)  DEFAULT NULL COMMENT '展示名',
  `delete_flag`     varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`     datetime      DEFAULT NULL,
  `create_user`     varchar(20)   DEFAULT NULL,
  `update_time`     datetime      DEFAULT NULL,
  `update_user`     varchar(20)   DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_kb_term_from` (`from_entry_id`) USING BTREE,
  KEY `idx_kb_term_to` (`to_kind`, `to_id`(128)) USING BTREE,
  KEY `idx_kb_term_type` (`edge_type`, `status`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='治理：业务术语类型化边（指针物化，非第三 Catalog）';
