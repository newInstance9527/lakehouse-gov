-- ETL 编排：DAG / 节点 / 边 / 运行 / 发布水位

CREATE TABLE IF NOT EXISTS `ig_etl_dag` (
  `id`                varchar(20)   NOT NULL COMMENT '主键',
  `revision`          int           NOT NULL DEFAULT 1 COMMENT '乐观锁',
  `ws`                varchar(64)   NOT NULL DEFAULT 'default' COMMENT '工作空间',
  `dag_code`          varchar(128)  NOT NULL COMMENT '对外编码如 dag.trade_dwd',
  `name`              varchar(256)  NOT NULL COMMENT '展示名',
  `description`       varchar(1024) DEFAULT NULL COMMENT '描述',
  `cron`              varchar(64)   DEFAULT NULL COMMENT '调度表达式',
  `owner`             varchar(64)   DEFAULT NULL COMMENT '负责人',
  `status`            varchar(32)   NOT NULL DEFAULT 'draft' COMMENT 'draft/prod/paused',
  `ver`               varchar(32)   NOT NULL DEFAULT 'v0.1' COMMENT '版本',
  `env`               varchar(16)   NOT NULL DEFAULT 'dev' COMMENT 'dev/stg/prod',
  `default_engine`    varchar(32)   NOT NULL DEFAULT 'flink' COMMENT 'flink/spark/datax',
  `sla`               varchar(32)   DEFAULT NULL COMMENT 'SLA 如 06:00',
  `ds_workflow_code`  varchar(128)  DEFAULT NULL COMMENT 'DS 流程编码',
  `git_ref`           varchar(128)  DEFAULT NULL COMMENT 'Git tag/commit',
  `delete_flag`       varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`       datetime      DEFAULT NULL COMMENT '创建时间',
  `create_user`       varchar(20)   DEFAULT NULL COMMENT '创建用户',
  `update_time`       datetime      DEFAULT NULL COMMENT '修改时间',
  `update_user`       varchar(20)   DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_ig_etl_dag` (`ws`,`dag_code`) USING BTREE,
  KEY `idx_ig_etl_dag_status` (`status`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='接入：ETL DAG 任务头';

CREATE TABLE IF NOT EXISTS `ig_etl_node` (
  `id`                varchar(20)   NOT NULL COMMENT '主键',
  `dag_id`            varchar(20)   NOT NULL COMMENT 'ig_etl_dag.id',
  `node_key`          varchar(64)   NOT NULL COMMENT '图内稳定 id',
  `node_type`         varchar(32)   NOT NULL COMMENT '18 类算子之一',
  `name`              varchar(256)  DEFAULT NULL COMMENT '展示名',
  `meta`              varchar(512)  DEFAULT NULL COMMENT '副标题/摘要',
  `pos_x`             int           NOT NULL DEFAULT 0 COMMENT '画布 X',
  `pos_y`             int           NOT NULL DEFAULT 0 COMMENT '画布 Y',
  `conf_json`         mediumtext    COMMENT '节点参数 JSON',
  `resolved_engine`   varchar(32)   DEFAULT NULL COMMENT '最近 resolve 引擎',
  `delete_flag`       varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`       datetime      DEFAULT NULL COMMENT '创建时间',
  `create_user`       varchar(20)   DEFAULT NULL COMMENT '创建用户',
  `update_time`       datetime      DEFAULT NULL COMMENT '修改时间',
  `update_user`       varchar(20)   DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_ig_etl_node` (`dag_id`,`node_key`) USING BTREE,
  KEY `idx_ig_etl_node_type` (`node_type`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='接入：ETL DAG 节点';

CREATE TABLE IF NOT EXISTS `ig_etl_edge` (
  `id`                varchar(20)   NOT NULL COMMENT '主键',
  `dag_id`            varchar(20)   NOT NULL COMMENT 'ig_etl_dag.id',
  `from_node_key`     varchar(64)   NOT NULL COMMENT '源节点',
  `to_node_key`       varchar(64)   NOT NULL COMMENT '目标节点',
  `label`             varchar(128)  NOT NULL DEFAULT '' COMMENT '条件分支标签',
  `sort_no`           int           NOT NULL DEFAULT 0 COMMENT '排序',
  `delete_flag`       varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`       datetime      DEFAULT NULL COMMENT '创建时间',
  `create_user`       varchar(20)   DEFAULT NULL COMMENT '创建用户',
  `update_time`       datetime      DEFAULT NULL COMMENT '修改时间',
  `update_user`       varchar(20)   DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_ig_etl_edge` (`dag_id`,`from_node_key`,`to_node_key`,`label`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='接入：ETL DAG 边';

CREATE TABLE IF NOT EXISTS `ig_etl_run` (
  `id`                varchar(20)   NOT NULL COMMENT '主键',
  `run_id`            varchar(64)   NOT NULL COMMENT '对外运行号',
  `dag_id`            varchar(20)   NOT NULL COMMENT 'ig_etl_dag.id',
  `ws`                varchar(64)   DEFAULT 'default' COMMENT '工作空间',
  `env`               varchar(16)   NOT NULL DEFAULT 'stg' COMMENT 'dev/stg/prod',
  `trigger_type`      varchar(32)   NOT NULL DEFAULT 'manual' COMMENT 'manual/cron/backfill',
  `status`            varchar(32)   NOT NULL DEFAULT 'submitted' COMMENT 'submitted/running/success/failed',
  `ds_run_id`         varchar(128)  DEFAULT NULL COMMENT 'DS 实例 id',
  `ol_run_id`         varchar(128)  DEFAULT NULL COMMENT 'OpenLineage run',
  `message`           varchar(1024) DEFAULT NULL COMMENT '摘要',
  `started_at`        datetime      DEFAULT NULL COMMENT '开始',
  `finished_at`       datetime      DEFAULT NULL COMMENT '结束',
  `create_time`       datetime      DEFAULT NULL COMMENT '创建时间',
  `create_user`       varchar(20)   DEFAULT NULL COMMENT '创建用户',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_ig_etl_run` (`run_id`) USING BTREE,
  KEY `idx_ig_etl_run_dag` (`dag_id`) USING BTREE,
  KEY `idx_ig_etl_run_started` (`started_at`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='接入：ETL 运行实例';

CREATE TABLE IF NOT EXISTS `ig_etl_run_node` (
  `id`                varchar(20)   NOT NULL COMMENT '主键',
  `run_id`            varchar(64)   NOT NULL COMMENT 'ig_etl_run.run_id',
  `node_key`          varchar(64)   NOT NULL COMMENT '节点 key',
  `node_type`         varchar(32)   DEFAULT NULL COMMENT '节点类型',
  `status`            varchar(32)   NOT NULL DEFAULT 'pending' COMMENT 'pending/running/done/blocked/failed',
  `engine`            varchar(32)   DEFAULT NULL COMMENT '解析引擎',
  `log_ref`           varchar(512)  DEFAULT NULL COMMENT '日志指针',
  `message`           varchar(1024) DEFAULT NULL COMMENT '节点摘要',
  `started_at`        datetime      DEFAULT NULL COMMENT '开始',
  `finished_at`       datetime      DEFAULT NULL COMMENT '结束',
  `create_time`       datetime      DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_ig_etl_run_node` (`run_id`,`node_key`) USING BTREE,
  KEY `idx_ig_etl_run_node_run` (`run_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='接入：ETL 运行节点态';

CREATE TABLE IF NOT EXISTS `cb_etl_deploy_watermark` (
  `id`            varchar(20)  NOT NULL COMMENT '主键',
  `source_system` varchar(32)  NOT NULL DEFAULT 'deploy' COMMENT 'deploy/lineage_parse/ds_sync',
  `mark_key`      varchar(128) NOT NULL COMMENT '水位键如 dag:{id}',
  `mark_value`    varchar(256) NOT NULL COMMENT '水位值',
  `update_time`   datetime     DEFAULT NULL COMMENT '更新时间',
  `update_user`   varchar(20)  DEFAULT NULL COMMENT '更新用户',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_cb_etl_watermark` (`source_system`,`mark_key`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='技术：ETL 发布/解析水位';

-- 演示种子：交易域日批 DAG
INSERT INTO `ig_etl_dag`
(`id`,`revision`,`ws`,`dag_code`,`name`,`description`,`cron`,`owner`,`status`,`ver`,`env`,`default_engine`,`sla`,`ds_workflow_code`,`delete_flag`,`create_time`)
VALUES
('ed01',1,'default','dag.trade_dwd','交易域日批','订单 CDC → ODS → DWD → DWS → ADS','0 2 * * *','李明','prod','v1.2','prod','flink','06:00','WF_dag.trade_dwd','NOT_DELETE',NOW())
ON DUPLICATE KEY UPDATE `update_time`=NOW();

INSERT INTO `ig_etl_node`
(`id`,`dag_id`,`node_key`,`node_type`,`name`,`meta`,`pos_x`,`pos_y`,`conf_json`,`resolved_engine`,`delete_flag`,`create_time`)
VALUES
('en01','ed01','s1','source','CDC 订单库','Flink CDC',40,80,
 '{"dsId":"ds_mysql_trade","mode":"cdc","tables":["order_prod.s_order"],"pk":"order_id","startupMode":"latest-offset","serverTimeZone":"Asia/Shanghai"}',
 'flink','NOT_DELETE',NOW()),
('en02','ed01','o1','sink_iceberg','Iceberg ODS','ods_trade.s_order',280,80,
 '{"catalog":"prod_catalog","database":"ods_trade","table":"s_order","partition":"dt","writeMode":"append","pk":"order_id"}',
 'flink','NOT_DELETE',NOW()),
('en03','ed01','c1','clean','DWD 清洗','清洗+脱敏',520,140,
 '{"preset":"STANDARD","dedup":true,"dedupKeys":["order_id"],"fieldRules":[],"rules":{"maskCols":["buyer_mobile"]}}',
 'spark','NOT_DELETE',NOW()),
('en04','ed01','m1','mapping','标准映射','order_status → STD-C0021',760,140,
 '{"strategy":"码值 CASE","mapList":[{"src":"stat","dst":"order_status","type":"STRING","expr":"CASE","skip":false},{"src":"amount","dst":"pay_amt","type":"DECIMAL(18,2)","expr":"amount/100","skip":false}]}',
 'spark','NOT_DELETE',NOW()),
('en05','ed01','t1','transform','DWS 日汇总','dws_trade.dws_order_1d',1000,140,
 '{"engine":"spark","sql":"SELECT dt, SUM(pay_amt) AS gmv FROM dwd_order_detail GROUP BY dt","dialect":"ansi","partitionBy":"dt"}',
 'spark','NOT_DELETE',NOW()),
('en06','ed01','q1','quality','质量门禁','blockOnFail',1240,80,
 '{"rules":[{"code":"PK_UNIQUE","cols":"dt","enabled":true,"level":"error"}],"blockOnFail":true,"threshold":0.01}',
 'ds_sql','NOT_DELETE',NOW()),
('en07','ed01','k1','sink_iceberg','ADS Iceberg','ads.ads_gmv',1480,140,
 '{"catalog":"prod_catalog","database":"ads","table":"ads_gmv","partition":"dt","writeMode":"overwrite"}',
 'spark','NOT_DELETE',NOW())
ON DUPLICATE KEY UPDATE `update_time`=NOW();

INSERT INTO `ig_etl_edge`
(`id`,`dag_id`,`from_node_key`,`to_node_key`,`label`,`sort_no`,`delete_flag`,`create_time`)
VALUES
('ee01','ed01','s1','o1','',1,'NOT_DELETE',NOW()),
('ee02','ed01','o1','c1','',2,'NOT_DELETE',NOW()),
('ee03','ed01','c1','m1','',3,'NOT_DELETE',NOW()),
('ee04','ed01','m1','t1','',4,'NOT_DELETE',NOW()),
('ee05','ed01','t1','q1','',5,'NOT_DELETE',NOW()),
('ee06','ed01','q1','k1','',6,'NOT_DELETE',NOW())
ON DUPLICATE KEY UPDATE `update_time`=NOW();

INSERT INTO `cb_etl_deploy_watermark` (`id`,`source_system`,`mark_key`,`mark_value`,`update_time`)
VALUES ('ew01','deploy','dag:ed01','v1.2',NOW())
ON DUPLICATE KEY UPDATE `mark_value`=VALUES(`mark_value`),`update_time`=NOW();
