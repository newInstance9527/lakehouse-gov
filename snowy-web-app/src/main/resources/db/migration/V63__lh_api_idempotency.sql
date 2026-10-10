-- API 写操作幂等记录（申请 / 发布 / 契约变更）

CREATE TABLE IF NOT EXISTS `lh_api_idempotency` (
  `id`              varchar(20)   NOT NULL COMMENT '主键',
  `scope`           varchar(64)   NOT NULL COMMENT 'apply_ticket/release_create/release_publish/contract_change/contract_schema',
  `idem_key`        varchar(128)  NOT NULL COMMENT '客户端幂等键',
  `subject_id`      varchar(64)   NOT NULL DEFAULT '' COMMENT '操作人 userId；空串=匿名/系统',
  `req_hash`        varchar(64)   DEFAULT NULL COMMENT '请求体摘要；同键异体则拒绝',
  `status`          varchar(16)   NOT NULL DEFAULT 'processing' COMMENT 'processing/completed/failed',
  `resource_type`   varchar(64)   DEFAULT NULL,
  `resource_id`     varchar(64)   DEFAULT NULL,
  `response_json`   mediumtext    COMMENT '成功响应 JSON',
  `error_msg`       varchar(512)  DEFAULT NULL,
  `delete_flag`     varchar(32)   DEFAULT 'NOT_DELETE',
  `create_time`     datetime      DEFAULT NULL,
  `update_time`     datetime      DEFAULT NULL,
  `expire_at`       datetime      DEFAULT NULL COMMENT '过期后可复用同键',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_lh_idem_scope_subj_key` (`scope`, `subject_id`, `idem_key`) USING BTREE,
  KEY `idx_lh_idem_expire` (`expire_at`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='API 写幂等（申请/发布/契约）';
