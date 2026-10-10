-- 字段血缘缓存 + 数据质量规则/运行/门禁

CREATE TABLE IF NOT EXISTS `gov_lineage_field_edge` (
  `id`              varchar(20)   NOT NULL COMMENT '主键',
  `revision`        int           NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
  `status`          varchar(32)   NOT NULL DEFAULT 'active' COMMENT 'active/deprecated',
  `ws`              varchar(64)   DEFAULT 'default' COMMENT '工作空间',
  `remark`          varchar(512)  DEFAULT NULL COMMENT '备注',
  `from_table`      varchar(256)  NOT NULL COMMENT '源表',
  `from_field`      varchar(128)  NOT NULL COMMENT '源字段',
  `to_table`        varchar(256)  NOT NULL COMMENT '目标表',
  `to_field`        varchar(128)  NOT NULL COMMENT '目标字段',
  `transform_text`  varchar(1024) DEFAULT NULL COMMENT '变换说明',
  `confidence`      varchar(16)   NOT NULL DEFAULT 'explicit' COMMENT 'explicit/inferred',
  `etl_job_id`      varchar(64)   DEFAULT NULL COMMENT '来源 ETL 作业',
  `om_from_fqn`     varchar(512)  DEFAULT NULL COMMENT '可选 OM 源 FQN',
  `om_to_fqn`       varchar(512)  DEFAULT NULL COMMENT '可选 OM 目标 FQN',
  `delete_flag`     varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`     datetime      DEFAULT NULL COMMENT '创建时间',
  `create_user`     varchar(20)   DEFAULT NULL COMMENT '创建用户',
  `update_time`     datetime      DEFAULT NULL COMMENT '修改时间',
  `update_user`     varchar(20)   DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_gov_lin_edge` (`ws`,`from_table`(96),`from_field`,`to_table`(96),`to_field`,`etl_job_id`) USING BTREE,
  KEY `idx_gov_lin_from` (`from_table`(128),`from_field`) USING BTREE,
  KEY `idx_gov_lin_to` (`to_table`(128),`to_field`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='治理：字段血缘边(ETL/解析缓存)';

CREATE TABLE IF NOT EXISTS `cb_lineage_sync_watermark` (
  `id`            varchar(20)  NOT NULL COMMENT '主键',
  `source_system` varchar(32)  NOT NULL DEFAULT 'etl_parse' COMMENT '源系统 etl_parse/marquez/om',
  `mark_key`      varchar(128) NOT NULL COMMENT '水位键',
  `mark_value`    varchar(256) NOT NULL COMMENT '水位值',
  `update_time`   datetime     DEFAULT NULL COMMENT '更新时间',
  `update_user`   varchar(20)  DEFAULT NULL COMMENT '更新用户',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_cb_lin_watermark` (`source_system`,`mark_key`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='技术：血缘解析/投影水位';

CREATE TABLE IF NOT EXISTS `gov_dq_rule` (
  `id`            varchar(20)   NOT NULL COMMENT '主键',
  `revision`      int           NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
  `status`        varchar(32)   NOT NULL DEFAULT 'active' COMMENT 'active/deprecated',
  `ws`            varchar(64)   DEFAULT 'default' COMMENT '工作空间',
  `remark`        varchar(512)  DEFAULT NULL COMMENT '备注',
  `rule_code`     varchar(128)  NOT NULL COMMENT '规则编码如 PK_UNIQUE',
  `rule_type`     varchar(64)   NOT NULL COMMENT '主键唯一/非空/枚举/...',
  `rule_level`    varchar(32)   NOT NULL DEFAULT '技术' COMMENT '技术/标准/业务/时效',
  `scope`         varchar(16)   NOT NULL DEFAULT 'field' COMMENT 'field/table',
  `table_name`    varchar(256)  NOT NULL COMMENT '绑定表',
  `asset_id`      varchar(20)   DEFAULT NULL COMMENT 'gov_asset.id',
  `field_name`    varchar(128)  DEFAULT NULL COMMENT '字段名(表级可空)',
  `layer`         varchar(16)   DEFAULT NULL COMMENT 'ODS/DWD/DWS/ADS',
  `expr_text`     varchar(2048) DEFAULT NULL COMMENT 'SQL/DSL',
  `severity`    varchar(16)   NOT NULL DEFAULT 'alert' COMMENT 'alert/block',
  `enabled`       tinyint(1)    NOT NULL DEFAULT 1 COMMENT '是否启用',
  `om_test_fqn`   varchar(512)  DEFAULT NULL COMMENT '可选 OM Test FQN',
  `delete_flag`   varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`   datetime      DEFAULT NULL COMMENT '创建时间',
  `create_user`   varchar(20)   DEFAULT NULL COMMENT '创建用户',
  `update_time`   datetime      DEFAULT NULL COMMENT '修改时间',
  `update_user`   varchar(20)   DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_gov_dq_rule` (`ws`,`table_name`(128),`field_name`,`rule_code`) USING BTREE,
  KEY `idx_gov_dq_layer` (`layer`) USING BTREE,
  KEY `idx_gov_dq_sev` (`severity`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='治理：数据质量规则';

CREATE TABLE IF NOT EXISTS `gov_dq_rule_run` (
  `id`            varchar(20)   NOT NULL COMMENT '主键',
  `ws`            varchar(64)   DEFAULT 'default' COMMENT '工作空间',
  `rule_id`       varchar(20)   NOT NULL COMMENT 'gov_dq_rule.id',
  `pass`          tinyint(1)    NOT NULL DEFAULT 0 COMMENT '是否通过',
  `ok_rows`       bigint        DEFAULT NULL COMMENT '通过行数',
  `fail_rows`     bigint        DEFAULT NULL COMMENT '失败行数',
  `ok_pct`        decimal(8,4)  DEFAULT NULL COMMENT '通过占比0-100',
  `blocked`       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '是否触发DAG阻断',
  `job_run_id`    varchar(64)   DEFAULT NULL COMMENT 'DS/Flink run',
  `message`       varchar(1024) DEFAULT NULL COMMENT '摘要',
  `ran_at`        datetime      NOT NULL COMMENT '执行时间',
  `create_time`   datetime      DEFAULT NULL COMMENT '写入时间',
  `create_user`   varchar(20)   DEFAULT NULL COMMENT '写入用户/系统',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_gov_dq_run_rule` (`rule_id`) USING BTREE,
  KEY `idx_gov_dq_run_at` (`ran_at`) USING BTREE,
  KEY `idx_gov_dq_run_pass` (`pass`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='治理：质量规则运行流水';

CREATE TABLE IF NOT EXISTS `gov_dq_gate` (
  `id`              varchar(20)  NOT NULL COMMENT '主键',
  `revision`        int          NOT NULL DEFAULT 1 COMMENT '乐观锁',
  `ws`              varchar(64)  DEFAULT 'default' COMMENT '工作空间',
  `asset_id`        varchar(20)  DEFAULT NULL COMMENT '资产可选',
  `table_name`      varchar(256) DEFAULT NULL COMMENT '表名可选',
  `layer`           varchar(16)  DEFAULT NULL COMMENT '层可选',
  `min_score`       decimal(8,2) NOT NULL DEFAULT 95.00 COMMENT '最低质量分',
  `block_on_fail`   tinyint(1)   NOT NULL DEFAULT 1 COMMENT '失败是否阻断',
  `delete_flag`     varchar(32)  DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`     datetime     DEFAULT NULL COMMENT '创建时间',
  `create_user`     varchar(20)  DEFAULT NULL COMMENT '创建用户',
  `update_time`     datetime     DEFAULT NULL COMMENT '修改时间',
  `update_user`     varchar(20)  DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_gov_dq_gate_table` (`table_name`(128)) USING BTREE,
  KEY `idx_gov_dq_gate_layer` (`layer`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='治理：质量门禁阈值';

CREATE TABLE IF NOT EXISTS `cb_dq_sync_watermark` (
  `id`            varchar(20)  NOT NULL COMMENT '主键',
  `source_system` varchar(32)  NOT NULL DEFAULT 'om_dq' COMMENT 'om_dq/ds_job',
  `mark_key`      varchar(128) NOT NULL COMMENT '水位键',
  `mark_value`    varchar(256) NOT NULL COMMENT '水位值',
  `update_time`   datetime     DEFAULT NULL COMMENT '更新时间',
  `update_user`   varchar(20)  DEFAULT NULL COMMENT '更新用户',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_cb_dq_watermark` (`source_system`,`mark_key`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='技术：质量与OM/作业同步水位';

-- 演示种子：交易域 GMV 字段边
INSERT INTO `gov_lineage_field_edge`
(`id`,`revision`,`status`,`ws`,`from_table`,`from_field`,`to_table`,`to_field`,`transform_text`,`confidence`,`etl_job_id`,`delete_flag`,`create_time`)
VALUES
('le01',1,'active','default','ods_trade.s_order','order_id','dwd_trade.dwd_order_detail','order_id','直接映射','explicit','etl_dwd_order','NOT_DELETE',NOW()),
('le02',1,'active','default','ods_trade.s_order','pay_amt','dwd_trade.dwd_order_detail','pay_amt','分→元 /100','explicit','etl_dwd_order','NOT_DELETE',NOW()),
('le03',1,'active','default','ods_trade.s_order','stat','dwd_trade.dwd_order_detail','order_status','CASE→STD-C0021','explicit','etl_dwd_order','NOT_DELETE',NOW()),
('le04',1,'active','default','dwd_trade.dwd_order_detail','pay_amt','dws_trade.dws_order_1d','gmv','SUM(pay_amt)','explicit','etl_dws_order','NOT_DELETE',NOW()),
('le05',1,'active','default','dwd_trade.dwd_order_detail','order_id','dws_trade.dws_order_1d','order_cnt','COUNT(DISTINCT)','explicit','etl_dws_order','NOT_DELETE',NOW()),
('le06',1,'active','default','dws_trade.dws_order_1d','gmv','ads.ads_gmv','gmv','日汇总投影','explicit','etl_ads_gmv','NOT_DELETE',NOW()),
('le07',1,'active','default','dwd_trade.dwd_order_detail','pay_amt','ads.ads_gmv','gmv','明细聚合(并行)','inferred','etl_ads_gmv','NOT_DELETE',NOW())
ON DUPLICATE KEY UPDATE `update_time`=NOW();

INSERT INTO `cb_lineage_sync_watermark` (`id`,`source_system`,`mark_key`,`mark_value`,`update_time`)
VALUES ('lw01','etl_parse','ws:default','seed-v8',NOW())
ON DUPLICATE KEY UPDATE `mark_value`=VALUES(`mark_value`),`update_time`=NOW();

-- 演示种子：质量规则 + 最近运行
INSERT INTO `gov_dq_rule`
(`id`,`revision`,`status`,`ws`,`rule_code`,`rule_type`,`rule_level`,`scope`,`table_name`,`field_name`,`layer`,`expr_text`,`severity`,`enabled`,`delete_flag`,`create_time`)
VALUES
('dq01',1,'active','default','PK_UNIQUE','主键唯一','技术','field','ods_trade.s_order','order_id','ODS','SELECT order_id, COUNT(*) c FROM T GROUP BY order_id HAVING c > 1','alert',1,'NOT_DELETE',NOW()),
('dq02',1,'active','default','PK_UNIQUE','主键唯一','技术','field','dwd_trade.dwd_order_detail','order_id','DWD','SELECT order_id, COUNT(*) c FROM T GROUP BY order_id HAVING c > 1','block',1,'NOT_DELETE',NOW()),
('dq03',1,'active','default','NOT_NULL','非空','技术','field','dwd_trade.dwd_order_detail','pay_amt','DWD','pay_amt IS NOT NULL','block',1,'NOT_DELETE',NOW()),
('dq04',1,'active','default','CODE_ORDER_STATUS','枚举','标准','field','dwd_trade.dwd_order_detail','order_status','DWD','order_status IN STD-C0021','alert',1,'NOT_DELETE',NOW()),
('dq05',1,'active','default','GMV_NONNEG','范围','业务','field','ads.ads_gmv','gmv','ADS','gmv >= 0','alert',1,'NOT_DELETE',NOW())
ON DUPLICATE KEY UPDATE `update_time`=NOW();

INSERT INTO `gov_dq_rule_run`
(`id`,`ws`,`rule_id`,`pass`,`ok_rows`,`fail_rows`,`ok_pct`,`blocked`,`job_run_id`,`message`,`ran_at`,`create_time`)
VALUES
('dr01','default','dq01',0,231753043,2814848,98.8000,0,'demo-run-1','主键重复 1.2%',DATE_SUB(NOW(), INTERVAL 2 HOUR),NOW()),
('dr02','default','dq02',0,98442112,1189021,98.8000,1,'demo-run-2','已阻断下游 DWS',DATE_SUB(NOW(), INTERVAL 1 HOUR),NOW()),
('dr03','default','dq03',1,99631133,0,100.0000,0,'demo-run-2','通过',DATE_SUB(NOW(), INTERVAL 1 HOUR),NOW()),
('dr04','default','dq04',0,99000000,631133,99.3700,0,'demo-run-2','未映射码值',DATE_SUB(NOW(), INTERVAL 50 MINUTE),NOW()),
('dr05','default','dq05',1,30,0,100.0000,0,'demo-run-3','通过',DATE_SUB(NOW(), INTERVAL 30 MINUTE),NOW())
ON DUPLICATE KEY UPDATE `ran_at`=VALUES(`ran_at`);

INSERT INTO `gov_dq_gate` (`id`,`revision`,`ws`,`layer`,`min_score`,`block_on_fail`,`delete_flag`,`create_time`)
VALUES ('dg01',1,'default','DWD',95.00,1,'NOT_DELETE',NOW())
ON DUPLICATE KEY UPDATE `min_score`=VALUES(`min_score`);
