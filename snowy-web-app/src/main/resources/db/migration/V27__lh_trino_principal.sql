-- 人机身份映射。不是表 ACL：库/表/列/行只存在于 Gravitino。
-- 作业服务账号不进本表。

CREATE TABLE IF NOT EXISTS `lh_trino_principal` (
  `id`              varchar(20)  NOT NULL COMMENT '主键',
  `portal_user_id`  varchar(64)  NOT NULL COMMENT '门户用户 id',
  `portal_account`  varchar(64)  NOT NULL COMMENT '门户登录名，仅展示',
  `trino_user`      varchar(64)  NOT NULL COMMENT 'Gravitino/Trino 主体',
  `kind`            varchar(16)  NOT NULL DEFAULT 'human' COMMENT '仅 human；作业账号禁止写入',
  `status`          varchar(16)  NOT NULL DEFAULT 'active' COMMENT 'active/disabled',
  `remark`          varchar(256) DEFAULT NULL COMMENT '备注',
  `delete_flag`     varchar(32)  DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`     datetime     DEFAULT NULL COMMENT '创建时间',
  `create_user`     varchar(20)  DEFAULT NULL COMMENT '创建用户',
  `update_time`     datetime     DEFAULT NULL COMMENT '修改时间',
  `update_user`     varchar(20)  DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_lh_trino_principal_user` (`portal_user_id`) USING BTREE,
  UNIQUE KEY `uq_lh_trino_principal_trino` (`trino_user`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='门户用户到 Gravitino 主体（非表权限）';
