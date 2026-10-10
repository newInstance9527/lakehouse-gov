-- 投影：OM/安全敏感标签 → Grav/Trino 列级 mask（即席 maskCols SoT）

CREATE TABLE IF NOT EXISTS `sec_mask_policy_proj` (
  `id`             varchar(20)  NOT NULL COMMENT '主键',
  `revision`       int          NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
  `status`         varchar(32)  DEFAULT 'active' COMMENT 'active/stale/revoked/error',
  `ws`             varchar(64)  DEFAULT 'default' COMMENT '工作空间',
  `remark`         varchar(512) DEFAULT NULL COMMENT '备注',
  `grav_asset_id`  varchar(20)  NOT NULL COMMENT 'cb_grav_asset_ref.id',
  `column_name`    varchar(128) NOT NULL COMMENT '列名',
  `sensitivity`    varchar(32)  NOT NULL DEFAULT 'sensitive' COMMENT '敏感级别',
  `mask_algo`      varchar(64)  NOT NULL DEFAULT 'engine' COMMENT '脱敏算法/引擎策略名',
  `om_tag_fqn`     varchar(512) DEFAULT NULL COMMENT 'OM标签FQN',
  `grav_policy_id` varchar(128) DEFAULT NULL COMMENT 'Gravitino/Trino策略ID',
  `delete_flag`    varchar(32)  DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`    datetime     DEFAULT NULL COMMENT '创建时间',
  `create_user`    varchar(20)  DEFAULT NULL COMMENT '创建用户',
  `update_time`    datetime     DEFAULT NULL COMMENT '修改时间',
  `update_user`    varchar(20)  DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_sec_mask` (`grav_asset_id`,`column_name`) USING BTREE,
  KEY `idx_sec_mask_status` (`status`,`delete_flag`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='投影：OM敏感标签到列级mask';
