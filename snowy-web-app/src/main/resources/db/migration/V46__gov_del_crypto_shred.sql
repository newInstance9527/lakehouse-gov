-- J3 / 合规 §2.12：crypto-shredding 密钥登记 + 载体扩展
-- DEK 明文只在 Vault；本表仅 fingerprint / vault_path / 状态，禁存主体明文与 DEK 材料

CREATE TABLE IF NOT EXISTS `gov_del_subject_dek` (
  `id`               varchar(20)   NOT NULL COMMENT '主键',
  `revision`         int           NOT NULL DEFAULT 1 COMMENT '乐观锁',
  `status`           varchar(32)   NOT NULL DEFAULT 'active' COMMENT 'active/shredded',
  `ws`               varchar(64)   DEFAULT 'default' COMMENT '工作空间',
  `remark`           varchar(512)  DEFAULT NULL COMMENT '备注',
  `subject_type`     varchar(32)   NOT NULL DEFAULT 'user' COMMENT '主体类型',
  `subject_id_hash`  varchar(64)   NOT NULL COMMENT 'HMAC-SHA256(主体)；禁止明文',
  `object_fqn`       varchar(512)  NOT NULL COMMENT '表 FQN（信封加密所在表）',
  `column_name`      varchar(128)  NOT NULL COMMENT 'PII 列名',
  `vault_path`       varchar(256)  NOT NULL COMMENT 'DEK 在 Vault 的路径',
  `kek_ref`          varchar(256)  DEFAULT NULL COMMENT 'KEK Vault 路径或别名',
  `dek_fingerprint`  varchar(64)   DEFAULT NULL COMMENT 'wrapped DEK 的 sha256（审计用，非密钥）',
  `shred_req_id`     varchar(20)   DEFAULT NULL COMMENT '触发删钥的 gov_del_request.id',
  `shredded_at`      datetime      DEFAULT NULL COMMENT '删钥时间',
  `delete_flag`      varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`      datetime      DEFAULT NULL COMMENT '创建时间',
  `create_user`      varchar(20)   DEFAULT NULL COMMENT '创建用户',
  `update_time`      datetime      DEFAULT NULL COMMENT '修改时间',
  `update_user`      varchar(20)   DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_gov_del_dek` (`ws`, `subject_id_hash`, `object_fqn`, `column_name`) USING BTREE,
  KEY `idx_gov_del_dek_subject` (`subject_id_hash`, `status`) USING BTREE,
  KEY `idx_gov_del_dek_status` (`status`, `shredded_at`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='SoT：每主体 PII 列 DEK 登记（crypto-shredding；删钥即擦除）';
