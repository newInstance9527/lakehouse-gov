-- J5：湖/CK 分区对账流水；热路径与核心看板门禁读此表 + gov_metric_materialize.recon_ok

CREATE TABLE IF NOT EXISTS `recon_partition` (
  `id`            varchar(20)   NOT NULL COMMENT '主键',
  `metric_code`   varchar(64)   DEFAULT NULL COMMENT '关联指标编码（可空）',
  `grav_asset_id` varchar(20)   DEFAULT NULL COMMENT '湖表指针',
  `lake_table`    varchar(256)  DEFAULT NULL COMMENT 'Iceberg 表 FQN',
  `ck_database`   varchar(128)  DEFAULT NULL COMMENT 'CK库',
  `ck_table`      varchar(256)  DEFAULT NULL COMMENT 'CK表',
  `partition_key` varchar(128)  NOT NULL COMMENT '分区键如 dt=2026-09-22',
  `lake_metric`   json          NOT NULL COMMENT '湖侧指标',
  `ck_metric`     json          NOT NULL COMMENT 'CK侧指标',
  `diff_ratio`    decimal(18,8) DEFAULT NULL COMMENT '差异比例',
  `threshold`     decimal(18,8) NOT NULL DEFAULT 0.001 COMMENT '阈值',
  `status`        varchar(16)   NOT NULL COMMENT 'pass/fail/skipped',
  `golden_flag`   tinyint(1)    NOT NULL DEFAULT 0 COMMENT '是否黄金',
  `checked_at`    datetime      NOT NULL COMMENT '检测时间',
  `trace_id`      varchar(64)   DEFAULT NULL COMMENT '链路ID',
  `create_time`   datetime      DEFAULT NULL COMMENT '创建时间',
  `create_user`   varchar(20)   DEFAULT NULL COMMENT '创建用户',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_recon_part_status` (`status`,`checked_at`) USING BTREE,
  KEY `idx_recon_part_metric` (`metric_code`,`checked_at`) USING BTREE,
  KEY `idx_recon_part_ck` (`ck_database`,`ck_table`,`partition_key`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='流水：湖CK分区对账';

-- 种子：M-0001 热表对账通过（与 V41 物化登记对齐）
INSERT INTO `recon_partition`
(`id`,`metric_code`,`lake_table`,`ck_database`,`ck_table`,`partition_key`,`lake_metric`,`ck_metric`,`diff_ratio`,`threshold`,`status`,`golden_flag`,`checked_at`,`trace_id`,`create_time`)
VALUES
('rpseed01','M-0001','iceberg.ads.metric_m_0001_d','ads','metric_m_0001_d','dt=2026-09-22',
 '{"rows":12840,"checksum":"a1b2"}','{"rows":12840,"checksum":"a1b2"}',0.00000000,0.00100000,'pass',1,NOW(),'trc-j5-seed-pass',NOW());
