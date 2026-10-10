-- 合规防复活：抑制名单（P1）
-- 存 subject_id_hash，禁止主体明文；CDC / 批作业启动时拉取过滤

CREATE TABLE IF NOT EXISTS `gov_del_suppression` (
  `id`               varchar(20)   NOT NULL COMMENT '主键',
  `revision`         int           NOT NULL DEFAULT 1 COMMENT '乐观锁',
  `status`           varchar(32)   NOT NULL DEFAULT 'active' COMMENT 'active/released/expired',
  `ws`               varchar(64)   DEFAULT 'default' COMMENT '工作空间',
  `remark`           varchar(512)  DEFAULT NULL COMMENT '备注',
  `req_id`           varchar(20)   DEFAULT NULL COMMENT '来源 gov_del_request.id',
  `subject_type`     varchar(32)   NOT NULL DEFAULT 'user' COMMENT '主体类型',
  `subject_id_hash`  varchar(64)   NOT NULL COMMENT 'HMAC-SHA256(主体)；禁止明文',
  `object_fqn`       varchar(512)  NOT NULL DEFAULT '*' COMMENT '表 FQN；* = 主体级全表',
  `effective_at`     datetime      NOT NULL COMMENT '生效时间',
  `expires_at`       datetime      DEFAULT NULL COMMENT '失效时间；空=长期',
  `source`           varchar(64)   DEFAULT 'execute' COMMENT '来源：execute/manual/restrict',
  `delete_flag`      varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`      datetime      DEFAULT NULL COMMENT '创建时间',
  `create_user`      varchar(20)   DEFAULT NULL COMMENT '创建用户',
  `update_time`      datetime      DEFAULT NULL COMMENT '修改时间',
  `update_user`      varchar(20)   DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_gov_del_supp` (`ws`, `subject_id_hash`, `object_fqn`) USING BTREE,
  KEY `idx_gov_del_supp_active` (`status`, `effective_at`, `expires_at`) USING BTREE,
  KEY `idx_gov_del_supp_fqn` (`object_fqn`) USING BTREE,
  KEY `idx_gov_del_supp_req` (`req_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='SoT：合规抑制名单（防 CDC/回算复活；仅 hash）';
