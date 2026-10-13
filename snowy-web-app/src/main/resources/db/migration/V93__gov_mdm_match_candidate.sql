-- MDM-H1：精确键匹配候选（人工确认合并；§6.2 旁路：门户主数据治理，非第三 Catalog）

CREATE TABLE IF NOT EXISTS `gov_mdm_match_candidate` (
  `id`               varchar(20)   NOT NULL COMMENT '主键',
  `revision`         int           NOT NULL DEFAULT 1,
  `status`           varchar(32)   NOT NULL DEFAULT 'open' COMMENT 'open/accepted/dismissed',
  `ws`               varchar(64)   DEFAULT 'default',
  `remark`           varchar(512)  DEFAULT NULL,
  `type_id`          varchar(20)   NOT NULL COMMENT 'gov_mdm_entity_type.id',
  `fingerprint`      varchar(256)  NOT NULL COMMENT '匹配指纹（规范化键值）',
  `match_keys_json`  varchar(512)  DEFAULT NULL COMMENT '生成时使用的匹配键',
  `left_record_id`   varchar(20)   NOT NULL COMMENT '候选左记录',
  `right_record_id`  varchar(20)   NOT NULL COMMENT '候选右记录',
  `delete_flag`      varchar(32)   DEFAULT 'NOT_DELETE',
  `create_time`      datetime      DEFAULT NULL,
  `create_user`      varchar(20)   DEFAULT NULL,
  `update_time`      datetime      DEFAULT NULL,
  `update_user`      varchar(20)   DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_mdm_match_pair` (`ws`, `type_id`, `left_record_id`, `right_record_id`) USING BTREE,
  KEY `idx_mdm_match_type_status` (`type_id`, `status`) USING BTREE,
  KEY `idx_mdm_match_fp` (`fingerprint`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='MDM 匹配候选（精确键）';
