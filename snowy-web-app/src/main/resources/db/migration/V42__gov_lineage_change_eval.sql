-- H3：变更评估单落库 + DDL 阻断门禁（对接 cp_release gate 5）

CREATE TABLE IF NOT EXISTS `gov_lineage_change_eval` (
  `id`              varchar(20)   NOT NULL COMMENT '主键',
  `revision`        int           NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
  `ws`              varchar(64)   DEFAULT 'default' COMMENT '工作空间',
  `table_name`      varchar(256)  NOT NULL COMMENT '变更表',
  `field_name`      varchar(128)  NOT NULL COMMENT '变更字段',
  `to_type`         varchar(64)   DEFAULT NULL COMMENT '拟改类型',
  `impact_count`    int           NOT NULL DEFAULT 0 COMMENT '下游影响数',
  `impact_json`     mediumtext    DEFAULT NULL COMMENT '下游传播 JSON',
  `status`          varchar(32)   NOT NULL DEFAULT 'draft' COMMENT 'draft/pending/approved/rejected',
  `passed`          tinyint(1)    NOT NULL DEFAULT 0 COMMENT '是否通过',
  `ticket_id`       varchar(64)   DEFAULT NULL COMMENT '可选外部工单号',
  `message`         varchar(512)  DEFAULT NULL COMMENT '摘要',
  `delete_flag`     varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`     datetime      DEFAULT NULL COMMENT '创建时间',
  `create_user`     varchar(20)   DEFAULT NULL COMMENT '创建用户',
  `update_time`     datetime      DEFAULT NULL COMMENT '修改时间',
  `update_user`     varchar(20)   DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_gov_lin_eval_table` (`ws`,`table_name`(128),`field_name`) USING BTREE,
  KEY `idx_gov_lin_eval_status` (`status`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='治理：字段变更评估单';

CREATE TABLE IF NOT EXISTS `gov_lineage_ddl_block` (
  `id`              varchar(20)   NOT NULL COMMENT '主键',
  `revision`        int           NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
  `ws`              varchar(64)   DEFAULT 'default' COMMENT '工作空间',
  `table_name`      varchar(256)  NOT NULL COMMENT '阻断表',
  `field_name`      varchar(128)  DEFAULT NULL COMMENT '阻断字段(可空=整表)',
  `reason`          varchar(512)  DEFAULT NULL COMMENT '阻断原因',
  `active`          tinyint(1)    NOT NULL DEFAULT 1 COMMENT '是否生效',
  `eval_id`         varchar(20)   DEFAULT NULL COMMENT '关联评估单',
  `ticket_id`       varchar(64)   DEFAULT NULL COMMENT '阻断登记号',
  `delete_flag`     varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`     datetime      DEFAULT NULL COMMENT '创建时间',
  `create_user`     varchar(20)   DEFAULT NULL COMMENT '创建用户',
  `update_time`     datetime      DEFAULT NULL COMMENT '修改时间',
  `update_user`     varchar(20)   DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_gov_lin_ddl_table` (`ws`,`table_name`(128),`active`) USING BTREE,
  KEY `idx_gov_lin_ddl_eval` (`eval_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='治理：血缘 DDL 阻断登记';
