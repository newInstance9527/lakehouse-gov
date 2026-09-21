-- 合规删除 P0：主体索引 + 请求 + 删除计划 + 执行流水 + 证据 + 法务冻结
-- 规格：doc/合规删除.md（审批复用 apply_ticket(compliance_delete)；执行面复用 /lh/lifecycle/*）
-- 硬约束：本组表任何列不得存主体 ID 明文，仅 subject_id_hash（HMAC）+ vault_path

CREATE TABLE IF NOT EXISTS `gov_del_subject_map` (
  `id`            varchar(20)  NOT NULL COMMENT '主键',
  `revision`      int          NOT NULL DEFAULT 1 COMMENT '乐观锁',
  `status`        varchar(32)  DEFAULT 'active' COMMENT 'active/disabled',
  `ws`            varchar(64)  DEFAULT 'default' COMMENT '工作空间',
  `remark`        varchar(512) DEFAULT NULL COMMENT '备注',
  `subject_type`  varchar(32)  NOT NULL COMMENT '主体类型：user/device/account/contract',
  `carrier`       varchar(32)  NOT NULL COMMENT '载体：iceberg/ck/sink/export/platform/ai/meta/log/backup/kafka/source',
  `object_fqn`    varchar(512) NOT NULL COMMENT '对象：表 FQN / 目标 / 桶 / 索引',
  `id_column`     varchar(128) DEFAULT NULL COMMENT '主体 ID 所在列（代理键优先）',
  `join_path`     varchar(512) DEFAULT NULL COMMENT '无主体列时的关联路径，如 order_id→user_key',
  `delete_mode`   varchar(32)  NOT NULL DEFAULT 'cow' COMMENT 'cow/mor/drop_partition/ck_mutation/sink_delete/notify/purge/register/retention/manual',
  `scope_tpl`     varchar(256) DEFAULT NULL COMMENT '范围模板，如 dt>=2024-01',
  `owner`         varchar(64)  DEFAULT NULL COMMENT '负责人',
  `sensitivity`   varchar(16)  DEFAULT NULL COMMENT '公开/内部/秘密/机密',
  `verified_at`   datetime     DEFAULT NULL COMMENT '最近一次核验时间',
  `delete_flag`   varchar(32)  DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`   datetime     DEFAULT NULL COMMENT '创建时间',
  `create_user`   varchar(20)  DEFAULT NULL COMMENT '创建用户',
  `update_time`   datetime     DEFAULT NULL COMMENT '修改时间',
  `update_user`   varchar(20)  DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_gov_del_map` (`ws`, `subject_type`, `object_fqn`) USING BTREE,
  KEY `idx_gov_del_map_carrier` (`carrier`) USING BTREE,
  KEY `idx_gov_del_map_status` (`status`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='SoT：主体索引（主体在哪些载体、经什么列可定位）';

CREATE TABLE IF NOT EXISTS `gov_del_request` (
  `id`              varchar(20)   NOT NULL COMMENT '主键',
  `revision`        int           NOT NULL DEFAULT 1 COMMENT '乐观锁',
  `status`          varchar(32)   NOT NULL DEFAULT 'assessing' COMMENT 'assessing/pending_approval/scheduled/executing/verifying/partial_failed/done/archived/destroyed/restricted/on_hold/rejected',
  `ws`              varchar(64)   DEFAULT 'default' COMMENT '工作空间',
  `remark`          varchar(512)  DEFAULT NULL COMMENT '备注',
  `req_no`          varchar(32)   NOT NULL COMMENT '请求号 DEL-YYYY-NNNN',
  `subject_type`    varchar(32)   NOT NULL DEFAULT 'user' COMMENT '主体类型',
  `subject_id_hash` varchar(64)   NOT NULL COMMENT 'HMAC-SHA256(主体ID)；禁止存明文',
  `subject_masked`  varchar(64)   DEFAULT NULL COMMENT '掩码展示，如 user_8***41',
  `vault_path`      varchar(256)  DEFAULT NULL COMMENT '明文在 Vault 的路径（P0 仅登记）',
  `req_type`        varchar(32)   NOT NULL DEFAULT 'forget' COMMENT 'forget/erase_error/regulator/contract_expire/account_close',
  `legal_basis`     varchar(256)  DEFAULT NULL COMMENT '法律/业务依据',
  `scope_label`     varchar(64)   DEFAULT NULL COMMENT '指定行/分区/日志归档',
  `source_system`   varchar(64)   DEFAULT NULL COMMENT '来源系统（法务/客服/人工）',
  `source_ref`      varchar(128)  DEFAULT NULL COMMENT '来源单号',
  `ticket_no`       varchar(64)   DEFAULT NULL COMMENT 'apply_ticket.ticket_no（compliance_delete）',
  `deadline`        datetime      DEFAULT NULL COMMENT '截止日期（默认 15 工作日）',
  `exec_window`     datetime      DEFAULT NULL COMMENT '排期执行窗口',
  `executed_at`     datetime      DEFAULT NULL COMMENT '执行完成时间',
  `verified_at`     datetime      DEFAULT NULL COMMENT '验证通过时间',
  `destroy_after`   datetime      DEFAULT NULL COMMENT '备份/冷归档物理销毁观察到期',
  `hold_reason`     varchar(256)  DEFAULT NULL COMMENT '法务冻结原因',
  `applicant`       varchar(64)   DEFAULT NULL COMMENT '申请人',
  `delete_flag`     varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`     datetime      DEFAULT NULL COMMENT '创建时间',
  `create_user`     varchar(20)   DEFAULT NULL COMMENT '创建用户',
  `update_time`     datetime      DEFAULT NULL COMMENT '修改时间',
  `update_user`     varchar(20)   DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_gov_del_req_no` (`req_no`) USING BTREE,
  KEY `idx_gov_del_req_status` (`ws`, `status`, `create_time`) USING BTREE,
  KEY `idx_gov_del_req_subject` (`subject_id_hash`) USING BTREE,
  KEY `idx_gov_del_req_deadline` (`deadline`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='SoT：合规删除请求';

CREATE TABLE IF NOT EXISTS `gov_del_target` (
  `id`             varchar(20)   NOT NULL COMMENT '主键',
  `revision`       int           NOT NULL DEFAULT 1 COMMENT '乐观锁',
  `status`         varchar(32)   NOT NULL DEFAULT 'planned' COMMENT 'planned/excluded/running/done/failed/pending_receipt/registered/restricted',
  `ws`             varchar(64)   DEFAULT 'default' COMMENT '工作空间',
  `remark`         varchar(512)  DEFAULT NULL COMMENT '备注',
  `req_id`         varchar(20)   NOT NULL COMMENT 'gov_del_request.id',
  `carrier`        varchar(32)   NOT NULL COMMENT '载体类型（对齐 subject_map.carrier）',
  `carrier_order`  int           NOT NULL DEFAULT 50 COMMENT '执行排序：源→湖→下游→平台→备份',
  `object_fqn`     varchar(512)  NOT NULL COMMENT '对象',
  `scope_expr`     varchar(256)  DEFAULT NULL COMMENT '分区/谓词范围',
  `mode`           varchar(32)   NOT NULL DEFAULT 'cow' COMMENT '执行方式',
  `rows_est`       bigint        DEFAULT 0 COMMENT '预估命中行',
  `rows_verified`  bigint        DEFAULT NULL COMMENT '验证后残留行（应为 0）',
  `engine_ref`     varchar(256)  DEFAULT NULL COMMENT 'run_id / snapshot_id / mutation_id',
  `exclude_reason` varchar(256)  DEFAULT NULL COMMENT '排除或限制处理理由',
  `review_at`      datetime      DEFAULT NULL COMMENT '限制处理复查日',
  `source_map_id`  varchar(20)   DEFAULT NULL COMMENT '来源 gov_del_subject_map.id',
  `manual_added`   tinyint(1)    NOT NULL DEFAULT 0 COMMENT '是否人工补充',
  `delete_flag`    varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`    datetime      DEFAULT NULL COMMENT '创建时间',
  `create_user`    varchar(20)   DEFAULT NULL COMMENT '创建用户',
  `update_time`    datetime      DEFAULT NULL COMMENT '修改时间',
  `update_user`    varchar(20)   DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_gov_del_target_req` (`req_id`, `carrier_order`) USING BTREE,
  KEY `idx_gov_del_target_status` (`status`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='删除计划项（载体矩阵实例）';

CREATE TABLE IF NOT EXISTS `gov_del_exec` (
  `id`           varchar(20)   NOT NULL COMMENT '主键',
  `revision`     int           NOT NULL DEFAULT 1 COMMENT '乐观锁',
  `status`       varchar(32)   NOT NULL DEFAULT 'success' COMMENT 'queued/running/success/failed/skipped',
  `ws`           varchar(64)   DEFAULT 'default' COMMENT '工作空间',
  `remark`       varchar(512)  DEFAULT NULL COMMENT '备注',
  `req_id`       varchar(20)   NOT NULL COMMENT 'gov_del_request.id',
  `target_id`    varchar(20)   DEFAULT NULL COMMENT 'gov_del_target.id；请求级步骤为空',
  `step`         varchar(64)   NOT NULL COMMENT 'request.create/plan.assess/plan.dry-run/approval.submit/iceberg.delete/...',
  `exec_key`     varchar(64)   DEFAULT NULL COMMENT '幂等键',
  `run_id`       varchar(64)   DEFAULT NULL COMMENT 'gov_lc_run.id / DS run',
  `operator`     varchar(64)   DEFAULT NULL COMMENT '操作人',
  `detail`       varchar(1024) DEFAULT NULL COMMENT '结果摘要',
  `started_at`   datetime      DEFAULT NULL COMMENT '开始',
  `ended_at`     datetime      DEFAULT NULL COMMENT '结束',
  `delete_flag`  varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`  datetime      DEFAULT NULL COMMENT '创建时间',
  `create_user`  varchar(20)   DEFAULT NULL COMMENT '创建用户',
  `update_time`  datetime      DEFAULT NULL COMMENT '修改时间',
  `update_user`  varchar(20)   DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_gov_del_exec_req` (`req_id`, `create_time`) USING BTREE,
  KEY `idx_gov_del_exec_key` (`exec_key`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='执行与状态流水（APPEND，证据来源）';

CREATE TABLE IF NOT EXISTS `gov_del_evidence` (
  `id`           varchar(20)   NOT NULL COMMENT '主键',
  `revision`     int           NOT NULL DEFAULT 1 COMMENT '乐观锁',
  `status`       varchar(32)   DEFAULT 'ok' COMMENT 'ok/missing',
  `ws`           varchar(64)   DEFAULT 'default' COMMENT '工作空间',
  `remark`       varchar(512)  DEFAULT NULL COMMENT '备注',
  `req_id`       varchar(20)   NOT NULL COMMENT 'gov_del_request.id',
  `kind`         varchar(32)   NOT NULL COMMENT 'ticket/plan/exec_log/verify/receipt/statement/package',
  `title`        varchar(256)  DEFAULT NULL COMMENT '条目标题',
  `object_path`  varchar(512)  DEFAULT NULL COMMENT '对象存储路径',
  `sha256`       varchar(64)   DEFAULT NULL COMMENT '内容摘要',
  `content`      text          COMMENT '短正文/JSON 摘要',
  `delete_flag`  varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`  datetime      DEFAULT NULL COMMENT '创建时间',
  `create_user`  varchar(20)   DEFAULT NULL COMMENT '创建用户',
  `update_time`  datetime      DEFAULT NULL COMMENT '修改时间',
  `update_user`  varchar(20)   DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_gov_del_evi_req` (`req_id`, `kind`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='证据条目（APPEND）';

CREATE TABLE IF NOT EXISTS `gov_del_hold` (
  `id`              varchar(20)   NOT NULL COMMENT '主键',
  `revision`        int           NOT NULL DEFAULT 1 COMMENT '乐观锁',
  `status`          varchar(32)   NOT NULL DEFAULT 'active' COMMENT 'active/released',
  `ws`              varchar(64)   DEFAULT 'default' COMMENT '工作空间',
  `remark`          varchar(512)  DEFAULT NULL COMMENT '备注',
  `req_id`          varchar(20)   DEFAULT NULL COMMENT '关联请求（可空=主体级冻结）',
  `subject_id_hash` varchar(64)   NOT NULL COMMENT '主体 HMAC',
  `scope`           varchar(256)  DEFAULT NULL COMMENT '冻结范围',
  `reason`          varchar(256)  NOT NULL COMMENT '冻结原因：诉讼保全/监管调查/法定保存期',
  `hold_until`      datetime      DEFAULT NULL COMMENT '冻结至',
  `source`          varchar(64)   DEFAULT NULL COMMENT '来源：法务/监管/系统',
  `released_at`     datetime      DEFAULT NULL COMMENT '解除时间',
  `delete_flag`     varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`     datetime      DEFAULT NULL COMMENT '创建时间',
  `create_user`     varchar(20)   DEFAULT NULL COMMENT '创建用户',
  `update_time`     datetime      DEFAULT NULL COMMENT '修改时间',
  `update_user`     varchar(20)   DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_gov_del_hold_subject` (`subject_id_hash`, `status`) USING BTREE,
  KEY `idx_gov_del_hold_req` (`req_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='法务冻结 / 法定保存期（执行门闩）';

-- ─────────────────────────────────────────────────────────────
-- 主体索引种子：覆盖 doc/合规删除.md §3 载体矩阵的主要载体
-- ─────────────────────────────────────────────────────────────
INSERT INTO `gov_del_subject_map`
(`id`,`revision`,`status`,`ws`,`subject_type`,`carrier`,`object_fqn`,`id_column`,`join_path`,`delete_mode`,`scope_tpl`,`owner`,`sensitivity`,`verified_at`,`delete_flag`,`create_time`)
VALUES
('21001',1,'active','default','user','source','mysql.crm.user',           'user_id',  NULL,                      'sink_delete','pk',            'crm-dba',    '机密',NOW(),'NOT_DELETE',NOW()),
('21002',1,'active','default','user','iceberg','dwd_user.dwd_user_info',   'user_key', NULL,                      'cow',        'dt>=2024-01',   'user-owner', '机密',NOW(),'NOT_DELETE',NOW()),
('21003',1,'active','default','user','iceberg','dwd_trade.dwd_order_detail',NULL,      'order_id→user_key',       'cow',        'dt>=2024-01',   'trade-owner','秘密',NOW(),'NOT_DELETE',NOW()),
('21004',1,'active','default','user','iceberg','ods_trade.s_order',        'buyer_key',NULL,                      'mor',        'dt>=2024-01',   'trade-owner','秘密',NOW(),'NOT_DELETE',NOW()),
('21005',1,'active','default','user','iceberg','ads.ads_user_tags',        'user_key', NULL,                      'cow',        'all',           'bi-owner',   '秘密',NOW(),'NOT_DELETE',NOW()),
('21006',1,'active','default','user','ck','ads.user_tags_local',           'user_key', NULL,                      'ck_mutation','all',           'bi-owner',   '秘密',NOW(),'NOT_DELETE',NOW()),
('21007',1,'active','default','user','sink','mysql.crm.user_profile',      'user_id',  NULL,                      'sink_delete','pk',            'crm-owner',  '机密',NOW(),'NOT_DELETE',NOW()),
('21008',1,'active','default','user','export','apply_ticket.lake_export',  NULL,       '按 EXP 单号反查活跃副本',  'notify',     'active',        'sec-officer',NULL,  NOW(),'NOT_DELETE',NOW()),
('21009',1,'active','default','user','platform','cp_query_exec.export',    NULL,       '导出审计 → 对象路径',      'purge',      'all',           'platform',   NULL,  NOW(),'NOT_DELETE',NOW()),
('21010',1,'active','default','user','ai','gov_ai_kb_chunk',               NULL,       '原文 + pgvector 嵌入',     'purge',      'all',           'ai-owner',   '秘密',NULL,  'NOT_DELETE',NOW()),
('21011',1,'active','default','user','meta','openmetadata.sample_data',    NULL,       '高敏表样例行',             'purge',      'pii tables',    'platform',   NULL,  NULL,  'NOT_DELETE',NOW()),
('21012',1,'active','default','user','backup','minio://archive',           NULL,       '含主体的备份批次',         'register',   'rolling',       'ops',        NULL,  NULL,  'NOT_DELETE',NOW()),
('21013',1,'active','default','user','kafka','cdc.trade.s_order',          NULL,       '保留期到期自然消亡',       'retention',  '<=7d',          'platform',   '秘密',NULL,  'NOT_DELETE',NOW()),
('21014',1,'active','default','order','iceberg','ods_trade.s_order',       'order_id', NULL,                      'drop_partition','dt',          'trade-owner','秘密',NOW(),'NOT_DELETE',NOW()),
('21015',1,'active','default','contract','iceberg','ods_ext.partner_daily',NULL,       '按合同批次分区',           'drop_partition','dt',         'partner-ops',NULL,  NULL,  'NOT_DELETE',NOW());

-- ─────────────────────────────────────────────────────────────
-- 请求种子：对齐前端演示工单（状态改为新状态机口径）
-- subject_id_hash 为演示用占位摘要；明文仅在 Vault
-- ─────────────────────────────────────────────────────────────
INSERT INTO `gov_del_request`
(`id`,`revision`,`status`,`ws`,`req_no`,`subject_type`,`subject_id_hash`,`subject_masked`,`vault_path`,`req_type`,`legal_basis`,`scope_label`,`source_system`,`source_ref`,`ticket_no`,`deadline`,`exec_window`,`executed_at`,`verified_at`,`destroy_after`,`applicant`,`delete_flag`,`create_time`)
VALUES
('21101',1,'pending_approval','default','DEL-2026-0042','user','a1f3c8de42b7105f9c3b6a77d0e21c48f5b9042a6c1d8e37f4a05b6c7d8e9f01','user_8***41','lh/compliance/subject/DEL-2026-0042','forget','GDPR Art.17 / 个保法 §47 · 用户主动申请','指定行','法务系统','LG-20260901-07',NULL,DATE_ADD(NOW(), INTERVAL 2 DAY),NULL,NULL,NULL,NULL,'法务系统','NOT_DELETE',DATE_SUB(NOW(), INTERVAL 6 DAY)),
('21102',1,'scheduled','default','DEL-2026-0040','user','b2e4d9ef53c8216a0d4c7b88e1f32d59a6ca153b7d2e9f48a5b16c7d8e9f0a12','UID_88***41','lh/compliance/subject/DEL-2026-0040','forget','GDPR Art.17 · 用户主动申请','指定行','法务系统','LG-20260901-04','CD-000000040',DATE_ADD(NOW(), INTERVAL 4 DAY),DATE_ADD(CURDATE(), INTERVAL 1 DAY),NULL,NULL,NULL,'法务系统','NOT_DELETE',DATE_SUB(NOW(), INTERVAL 6 DAY)),
('21103',1,'done','default','DEL-2026-0038','order','c3f5e0fa64d9327b1e5d8c99f2a43e60b7db264c8e3fa059b6c27d8e9f0a1b23','order_batch_2026***','lh/compliance/subject/DEL-2026-0038','erase_error','错误导入批次纠错','分区','数据治理','DQ-20260828-11','CD-000000038',DATE_SUB(NOW(), INTERVAL 20 DAY),DATE_SUB(NOW(), INTERVAL 22 DAY),DATE_SUB(NOW(), INTERVAL 22 DAY),DATE_SUB(NOW(), INTERVAL 22 DAY),NULL,'数据治理','NOT_DELETE',DATE_SUB(NOW(), INTERVAL 24 DAY)),
('21104',1,'archived','default','DEL-2026-0031','user','d4a6f1ab75ea438c2f6e9daa03b54f71c8ec375d9f40b16ac7d38e9f0a1b2c34','user_7***20','lh/compliance/subject/DEL-2026-0031','forget','个保法 §47 · 用户注销','指定行','客服系统','CS-20260810-33','CD-000000031',DATE_SUB(NOW(), INTERVAL 30 DAY),DATE_SUB(NOW(), INTERVAL 38 DAY),DATE_SUB(NOW(), INTERVAL 38 DAY),DATE_SUB(NOW(), INTERVAL 38 DAY),DATE_ADD(NOW(), INTERVAL 20 DAY),'客服系统','NOT_DELETE',DATE_SUB(NOW(), INTERVAL 41 DAY)),
('21105',1,'rejected','default','DEL-2026-0027','contract','e5b702bc86fb549d306fa1bb14c65082d9fd486ea051c27bd8e49f0a1b2c3d45','partner_feed_07***','lh/compliance/subject/DEL-2026-0027','contract_expire','合作协议保留 90 天','日志归档','合作伙伴运营','PT-20260805-02','CD-000000027',DATE_SUB(NOW(), INTERVAL 36 DAY),NULL,NULL,NULL,NULL,'合作伙伴运营','NOT_DELETE',DATE_SUB(NOW(), INTERVAL 47 DAY)),
('21106',1,'assessing','default','DEL-2026-0045','user','f6c813cd97ac650e417ab2cc25d76193eafe597fb162d38ce9f50a1b2c3d4e56','user_9***01','lh/compliance/subject/DEL-2026-0045','regulator','监管函〔2026〕12 号','指定行','合规办公室','RG-20260903-01',NULL,DATE_ADD(NOW(), INTERVAL 9 DAY),NULL,NULL,NULL,NULL,'合规办公室','NOT_DELETE',DATE_SUB(NOW(), INTERVAL 4 DAY));

-- 计划项：0042 待执行计划；0104 含备份登记（待物理销毁）；0103 已完成
INSERT INTO `gov_del_target`
(`id`,`revision`,`status`,`ws`,`req_id`,`carrier`,`carrier_order`,`object_fqn`,`scope_expr`,`mode`,`rows_est`,`rows_verified`,`engine_ref`,`exclude_reason`,`review_at`,`source_map_id`,`manual_added`,`delete_flag`,`create_time`)
VALUES
('21201',1,'planned','default','21101','source',10,'mysql.crm.user','pk','sink_delete',1,NULL,NULL,NULL,NULL,'21001',0,'NOT_DELETE',NOW()),
('21202',1,'planned','default','21101','iceberg',20,'dwd_user.dwd_user_info','dt>=2024-01','cow',1204,NULL,NULL,NULL,NULL,'21002',0,'NOT_DELETE',NOW()),
('21203',1,'planned','default','21101','iceberg',20,'dwd_trade.dwd_order_detail','dt>=2024-01','cow',318,NULL,NULL,NULL,NULL,'21003',0,'NOT_DELETE',NOW()),
('21204',1,'planned','default','21101','iceberg',20,'ads.ads_user_tags','all','cow',12,NULL,NULL,NULL,NULL,'21005',0,'NOT_DELETE',NOW()),
('21205',1,'planned','default','21101','ck',30,'ads.user_tags_local','all','ck_mutation',1204,NULL,NULL,NULL,NULL,'21006',0,'NOT_DELETE',NOW()),
('21206',1,'planned','default','21101','sink',40,'mysql.crm.user_profile','pk','sink_delete',1,NULL,NULL,NULL,NULL,'21007',0,'NOT_DELETE',NOW()),
('21207',1,'planned','default','21101','export',50,'apply_ticket.lake_export','active','notify',0,NULL,NULL,NULL,NULL,'21008',0,'NOT_DELETE',NOW()),
('21208',1,'planned','default','21101','platform',60,'cp_query_exec.export','all','purge',3,NULL,NULL,NULL,NULL,'21009',0,'NOT_DELETE',NOW()),
('21209',1,'planned','default','21101','ai',60,'gov_ai_kb_chunk','all','purge',2,NULL,NULL,NULL,NULL,'21010',0,'NOT_DELETE',NOW()),
('21210',1,'planned','default','21101','backup',90,'minio://archive','rolling','register',0,NULL,NULL,NULL,NULL,'21012',0,'NOT_DELETE',NOW()),
('21211',1,'restricted','default','21101','kafka',95,'cdc.trade.s_order','<=7d','retention',0,NULL,NULL,'Kafka 不支持定点删除；依赖 7 天保留期到期，期间停止再消费',DATE_ADD(NOW(), INTERVAL 7 DAY),'21013',0,'NOT_DELETE',NOW()),
('21212',1,'done','default','21103','iceberg',20,'ods_trade.s_order','dt=2026-08','drop_partition',1248,0,'lc-run-stub-0038',NULL,NULL,'21014',0,'NOT_DELETE',DATE_SUB(NOW(), INTERVAL 24 DAY)),
('21213',1,'done','default','21104','iceberg',20,'dwd_user.dwd_user_info','dt>=2023-01','cow',842,0,'lc-run-stub-0031',NULL,NULL,'21002',0,'NOT_DELETE',DATE_SUB(NOW(), INTERVAL 41 DAY)),
('21214',1,'registered','default','21104','backup',90,'minio://archive/2026-07','含主体备份批次','register',0,NULL,'purge_due=2026-10-11',NULL,NULL,'21012',0,'NOT_DELETE',DATE_SUB(NOW(), INTERVAL 41 DAY));

INSERT INTO `gov_del_exec`
(`id`,`revision`,`status`,`ws`,`req_id`,`target_id`,`step`,`exec_key`,`run_id`,`operator`,`detail`,`started_at`,`ended_at`,`delete_flag`,`create_time`)
VALUES
('21301',1,'success','default','21101',NULL,'request.create',NULL,NULL,'法务系统','受理被遗忘权请求，SLA 15 工作日',DATE_SUB(NOW(), INTERVAL 6 DAY),DATE_SUB(NOW(), INTERVAL 6 DAY),'NOT_DELETE',DATE_SUB(NOW(), INTERVAL 6 DAY)),
('21302',1,'success','default','21101',NULL,'plan.assess',NULL,NULL,'system','命中主体索引 11 项；kafka 转限制处理',DATE_SUB(NOW(), INTERVAL 6 DAY),DATE_SUB(NOW(), INTERVAL 6 DAY),'NOT_DELETE',DATE_SUB(NOW(), INTERVAL 6 DAY)),
('21303',1,'success','default','21103',NULL,'iceberg.delete','exec-0038','lc-run-stub-0038','platform','分区 overwrite + equality delete，删除 1,248 行',DATE_SUB(NOW(), INTERVAL 22 DAY),DATE_SUB(NOW(), INTERVAL 22 DAY),'NOT_DELETE',DATE_SUB(NOW(), INTERVAL 22 DAY)),
('21304',1,'success','default','21103',NULL,'verify.residual','exec-0038',NULL,'platform','Trino 反查 0 行；旧快照不可读',DATE_SUB(NOW(), INTERVAL 22 DAY),DATE_SUB(NOW(), INTERVAL 22 DAY),'NOT_DELETE',DATE_SUB(NOW(), INTERVAL 22 DAY));

INSERT INTO `gov_del_evidence`
(`id`,`revision`,`status`,`ws`,`req_id`,`kind`,`title`,`object_path`,`sha256`,`content`,`delete_flag`,`create_time`)
VALUES
('21401',1,'ok','default','21103','verify','残留验证结果',NULL,NULL,'{"tables":1,"rowsVerified":0,"timeTravel":"unreadable"}','NOT_DELETE',DATE_SUB(NOW(), INTERVAL 22 DAY)),
('21402',1,'ok','default','21104','statement','合作方副本删除回执',NULL,NULL,'{"receipt":"partner-bi","note":"已回执确认删除"}','NOT_DELETE',DATE_SUB(NOW(), INTERVAL 38 DAY));

INSERT INTO `gov_del_hold`
(`id`,`revision`,`status`,`ws`,`req_id`,`subject_id_hash`,`scope`,`reason`,`hold_until`,`source`,`delete_flag`,`create_time`)
VALUES
('21501',1,'released','default','21105','e5b702bc86fb549d306fa1bb14c65082d9fd486ea051c27bd8e49f0a1b2c3d45','ods_ext.partner_daily','对账争议 · Owner 驳回后暂缓',NULL,'法务','NOT_DELETE',DATE_SUB(NOW(), INTERVAL 40 DAY));
