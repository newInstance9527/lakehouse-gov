-- 作业服务账号台账（§35.1）；凭证在 ig_secret_store，本表无明文
CREATE TABLE IF NOT EXISTS `sec_job_sa` (
  `id`               varchar(20)   NOT NULL COMMENT '主键',
  `revision`         int           NOT NULL DEFAULT 1 COMMENT '乐观锁',
  `ws`               varchar(64)   NOT NULL DEFAULT 'default' COMMENT '工作空间',
  `sa_name`          varchar(128)  NOT NULL COMMENT 'job.{domain}.{action}',
  `domain`           varchar(64)   DEFAULT NULL COMMENT '业务域',
  `job_bind`         varchar(1024) DEFAULT NULL COMMENT '绑定作业清单 JSON/逗号',
  `privilege_scope`  varchar(1024) DEFAULT NULL COMMENT '权限范围文案/JSON',
  `vault_path`       varchar(512)  NOT NULL COMMENT 'ig_secret_store 路径',
  `status`           varchar(32)   NOT NULL DEFAULT 'active' COMMENT 'active/retired',
  `expire_at`        datetime      DEFAULT NULL COMMENT '有效期',
  `delete_flag`      varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`      datetime      DEFAULT NULL COMMENT '创建时间',
  `create_user`      varchar(20)   DEFAULT NULL COMMENT '创建用户',
  `update_time`      datetime      DEFAULT NULL COMMENT '修改时间',
  `update_user`      varchar(20)   DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_sec_job_sa_ws_name` (`ws`, `sa_name`) USING BTREE,
  KEY `idx_sec_job_sa_status` (`status`) USING BTREE,
  KEY `idx_sec_job_sa_vault` (`vault_path`(191)) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='安全：作业服务账号台账';
