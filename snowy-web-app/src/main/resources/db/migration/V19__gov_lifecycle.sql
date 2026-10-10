-- 生命周期与小文件治理 P0：策略 SoT + 运行留痕 + 孤儿扫描 + 表存储投影 + 日作业步骤
-- 规格：doc/生命周期.md

CREATE TABLE IF NOT EXISTS `gov_lc_policy` (
  `id`                     varchar(20)  NOT NULL COMMENT '主键',
  `revision`               int          NOT NULL DEFAULT 1 COMMENT '乐观锁',
  `status`                 varchar(32)  DEFAULT 'active' COMMENT 'active/disabled',
  `ws`                     varchar(64)  DEFAULT 'default' COMMENT '工作空间',
  `remark`                 varchar(512) DEFAULT NULL COMMENT '备注',
  `table_fqn`              varchar(512) NOT NULL COMMENT '表 FQN，如 ods_trade.s_order',
  `keep_count`             int          NOT NULL DEFAULT 20 COMMENT '保留快照数',
  `keep_days`              int          NOT NULL DEFAULT 7 COMMENT '保留天数；CDC L1 可 3',
  `min_snapshots`          int          NOT NULL DEFAULT 5 COMMENT '最少快照数',
  `compact_level`          varchar(8)   DEFAULT 'L2' COMMENT 'L1/L2/L3',
  `target_file_mb`         int          DEFAULT 256 COMMENT 'rewrite 目标文件 MB',
  `orphan_older_days`      int          NOT NULL DEFAULT 7 COMMENT 'orphan older_than 天数',
  `orphan_safety_hours`    int          NOT NULL DEFAULT 72 COMMENT '相对 expire 安全窗小时',
  `partition_expire_days`  int          DEFAULT NULL COMMENT '分区过期天数（可空）',
  `layer`                  varchar(16)  DEFAULT NULL COMMENT 'ODS/DWD/DWS/ADS',
  `owner`                  varchar(64)  DEFAULT NULL COMMENT '表负责人',
  `delete_flag`            varchar(32)  DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`            datetime     DEFAULT NULL COMMENT '创建时间',
  `create_user`            varchar(20)  DEFAULT NULL COMMENT '创建用户',
  `update_time`            datetime     DEFAULT NULL COMMENT '修改时间',
  `update_user`            varchar(20)  DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_gov_lc_policy_fqn` (`ws`, `table_fqn`) USING BTREE,
  KEY `idx_gov_lc_policy_level` (`compact_level`) USING BTREE,
  KEY `idx_gov_lc_policy_status` (`status`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='SoT：生命周期策略（每表）';

CREATE TABLE IF NOT EXISTS `gov_lc_run` (
  `id`            varchar(20)   NOT NULL COMMENT '主键=run_id',
  `revision`      int           NOT NULL DEFAULT 1 COMMENT '乐观锁',
  `status`        varchar(32)   DEFAULT 'queued' COMMENT 'queued/running/success/failed',
  `ws`            varchar(64)   DEFAULT 'default' COMMENT '工作空间',
  `remark`        varchar(512)  DEFAULT NULL COMMENT '备注',
  `kind`          varchar(32)   NOT NULL COMMENT 'compact/expire/orphan/daily/partition',
  `table_fqn`     varchar(512)  DEFAULT NULL COMMENT '单表动作时的 FQN；daily 可空',
  `batch_id`      varchar(20)   DEFAULT NULL COMMENT '日作业批次；关联 job_step',
  `ds_task_id`    varchar(128)  DEFAULT NULL COMMENT 'DS 任务/实例 ID（骨架可占位）',
  `dry_run`       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '是否 dry-run',
  `metrics_json`  text          COMMENT '结果指标 JSON',
  `error_msg`     varchar(1024) DEFAULT NULL COMMENT '失败原因',
  `operator`      varchar(64)   DEFAULT NULL COMMENT '触发人（登录名/展示名）',
  `started_at`    datetime      DEFAULT NULL COMMENT '开始',
  `finished_at`   datetime      DEFAULT NULL COMMENT '结束',
  `delete_flag`   varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`   datetime      DEFAULT NULL COMMENT '创建时间',
  `create_user`   varchar(20)   DEFAULT NULL COMMENT '创建用户',
  `update_time`   datetime      DEFAULT NULL COMMENT '修改时间',
  `update_user`   varchar(20)   DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_gov_lc_run_ws_kind` (`ws`, `kind`, `create_time`) USING BTREE,
  KEY `idx_gov_lc_run_table` (`table_fqn`) USING BTREE,
  KEY `idx_gov_lc_run_batch` (`batch_id`) USING BTREE,
  KEY `idx_gov_lc_run_status` (`status`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='生命周期执行留痕';

CREATE TABLE IF NOT EXISTS `gov_lc_orphan_scan` (
  `id`               varchar(20)   NOT NULL COMMENT '主键=scan_id',
  `revision`         int           NOT NULL DEFAULT 1 COMMENT '乐观锁',
  `status`           varchar(32)   DEFAULT 'done' COMMENT 'running/done/failed',
  `ws`               varchar(64)   DEFAULT 'default' COMMENT '工作空间',
  `remark`           varchar(512)  DEFAULT NULL COMMENT '备注',
  `bucket`           varchar(128)  NOT NULL COMMENT '桶名',
  `candidate_count`  bigint        DEFAULT 0 COMMENT '候选孤儿文件数',
  `bytes`            bigint        DEFAULT 0 COMMENT '预估回收字节',
  `window_ok`        tinyint(1)    DEFAULT 0 COMMENT '安全窗是否满足',
  `dry_run`          tinyint(1)    NOT NULL DEFAULT 1 COMMENT '默认 dry-run',
  `result_uri`       varchar(512)  DEFAULT NULL COMMENT '结果清单对象路径',
  `run_id`           varchar(20)   DEFAULT NULL COMMENT '关联 gov_lc_run.id',
  `delete_flag`      varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`      datetime      DEFAULT NULL COMMENT '创建时间',
  `create_user`      varchar(20)   DEFAULT NULL COMMENT '创建用户',
  `update_time`      datetime      DEFAULT NULL COMMENT '修改时间',
  `update_user`      varchar(20)   DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_gov_lc_orphan_ws` (`ws`, `create_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='孤儿扫描结果（P0 dry-run）';

CREATE TABLE IF NOT EXISTS `gov_lc_table_stat` (
  `id`              varchar(20)   NOT NULL COMMENT '主键',
  `revision`        int           NOT NULL DEFAULT 1 COMMENT '乐观锁',
  `status`          varchar(32)   DEFAULT 'ok' COMMENT 'ok/warn/danger',
  `ws`              varchar(64)   DEFAULT 'default' COMMENT '工作空间',
  `remark`          varchar(512)  DEFAULT NULL COMMENT '备注',
  `table_fqn`       varchar(512)  NOT NULL COMMENT '表 FQN',
  `layer`           varchar(16)   DEFAULT NULL COMMENT '分层',
  `size_bytes`      bigint        DEFAULT 0 COMMENT '占用字节',
  `file_count`      bigint        DEFAULT 0 COMMENT '数据文件数',
  `avg_file_bytes`  bigint        DEFAULT 0 COMMENT '平均文件字节',
  `growth_7d_pct`   decimal(8,2)  DEFAULT 0 COMMENT '近7日增速百分比',
  `snapshot_count`  int           DEFAULT NULL COMMENT '当前快照数',
  `policy_label`    varchar(64)   DEFAULT NULL COMMENT '策略摘要展示',
  `collected_at`    datetime      DEFAULT NULL COMMENT '采集时间（VM/作业回写）',
  `delete_flag`     varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`     datetime      DEFAULT NULL COMMENT '创建时间',
  `create_user`     varchar(20)   DEFAULT NULL COMMENT '创建用户',
  `update_time`     datetime      DEFAULT NULL COMMENT '修改时间',
  `update_user`     varchar(20)   DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_gov_lc_stat_fqn` (`ws`, `table_fqn`) USING BTREE,
  KEY `idx_gov_lc_stat_growth` (`growth_7d_pct`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='表存储投影（P0 种子；正式由采集回写）';

CREATE TABLE IF NOT EXISTS `gov_lc_job_step` (
  `id`            varchar(20)   NOT NULL COMMENT '主键',
  `revision`      int           NOT NULL DEFAULT 1 COMMENT '乐观锁',
  `status`        varchar(32)   DEFAULT 'success' COMMENT 'success/warn/failed/queued/running',
  `ws`            varchar(64)   DEFAULT 'default' COMMENT '工作空间',
  `remark`        varchar(512)  DEFAULT NULL COMMENT '备注',
  `batch_id`      varchar(20)   NOT NULL COMMENT '日作业批次',
  `step_no`       int           NOT NULL COMMENT '1..4',
  `step_name`     varchar(64)   NOT NULL COMMENT 'expire_snapshots 等',
  `step_desc`     varchar(128)  DEFAULT NULL COMMENT '中文描述',
  `detail`        varchar(512)  DEFAULT NULL COMMENT '详情',
  `duration_sec`  int           DEFAULT NULL COMMENT '耗时秒',
  `delete_flag`   varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`   datetime      DEFAULT NULL COMMENT '创建时间',
  `create_user`   varchar(20)   DEFAULT NULL COMMENT '创建用户',
  `update_time`   datetime      DEFAULT NULL COMMENT '修改时间',
  `update_user`   varchar(20)   DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_gov_lc_job_step` (`batch_id`, `step_no`) USING BTREE,
  KEY `idx_gov_lc_job_ws` (`ws`, `batch_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='日作业四步快照';

-- 策略种子（对齐前端 LC_SNAPSHOT_POLICIES / LC_COMPACTION）
INSERT INTO `gov_lc_policy`
(`id`,`revision`,`status`,`ws`,`table_fqn`,`keep_count`,`keep_days`,`min_snapshots`,`compact_level`,`target_file_mb`,`orphan_older_days`,`orphan_safety_hours`,`partition_expire_days`,`layer`,`owner`,`delete_flag`,`create_time`)
VALUES
('19001',1,'active','default','ods_trade.s_order',20,3,5,'L1',256,7,72,90,'ODS','trade-owner','NOT_DELETE',NOW()),
('19002',1,'active','default','dwd_trade.dwd_order_detail',20,7,5,'L2',256,7,72,NULL,'DWD','trade-owner','NOT_DELETE',NOW()),
('19003',1,'active','default','dwd_order_detail',20,7,5,'L2',256,7,72,NULL,'DWD','trade-owner','NOT_DELETE',NOW()),
('19004',1,'active','default','dws_trade.dws_order_1d',15,7,5,'L3',128,7,72,NULL,'DWS','trade-owner','NOT_DELETE',NOW()),
('19005',1,'active','default','dws_order_1d',15,7,5,'L3',128,7,72,NULL,'DWS','trade-owner','NOT_DELETE',NOW()),
('19006',1,'active','default','ads.ads_gmv_board',10,14,3,'L3',512,7,72,NULL,'ADS','bi-owner','NOT_DELETE',NOW()),
('19007',1,'active','default','ads_gmv_board',10,14,3,'L3',512,7,72,NULL,'ADS','bi-owner','NOT_DELETE',NOW()),
('19008',1,'active','default','dwd_log_action',20,7,5,'L2',128,7,72,NULL,'DWD','ops-owner','NOT_DELETE',NOW());

-- 表存储投影种子（对齐 LC_STORAGE / LC_COMPACTION）
INSERT INTO `gov_lc_table_stat`
(`id`,`revision`,`status`,`ws`,`table_fqn`,`layer`,`size_bytes`,`file_count`,`avg_file_bytes`,`growth_7d_pct`,`snapshot_count`,`policy_label`,`collected_at`,`delete_flag`,`create_time`)
VALUES
('19101',1,'ok','default','dwd_order_detail','DWD',904000000000,412,28000000,2.10,18,'温·7天快照',NOW(),'NOT_DELETE',NOW()),
('19102',1,'warn','default','ods_trade.s_order','ODS',1319000000000,1280,12000000,3.80,22,'热→温 90天',NOW(),'NOT_DELETE',NOW()),
('19103',1,'warn','default','dwd_log_action','DWD',665000000000,890,18000000,8.50,16,'需小文件合并',NOW(),'NOT_DELETE',NOW()),
('19104',1,'ok','default','dws_order_1d','DWS',137000000000,48,268000000,0.30,12,'冷归档候选',NOW(),'NOT_DELETE',NOW()),
('19105',1,'ok','default','ads_gmv_board','ADS',12900000000,16,536000000,5.20,8,'热·CK',NOW(),'NOT_DELETE',NOW());

-- 最近一次日作业四步（演示）
INSERT INTO `gov_lc_job_step`
(`id`,`revision`,`status`,`ws`,`batch_id`,`step_no`,`step_name`,`step_desc`,`detail`,`duration_sec`,`delete_flag`,`create_time`)
VALUES
('19201',1,'success','default','19200',1,'expire_snapshots','快照过期','ods/dwd 按保留策略逻辑删除',480,'NOT_DELETE',NOW()),
('19202',1,'success','default','19200',2,'rewrite_data_files','小文件合并（目标 256MB）','L1/L2 表 compaction',2520,'NOT_DELETE',NOW()),
('19203',1,'success','default','19200',3,'remove_orphan_files','孤儿文件清理','快照过期 +72h 后物理删',900,'NOT_DELETE',NOW()),
('19204',1,'success','default','19200',4,'expire_partitions','分区过期','ODS 90 天前归档候选',360,'NOT_DELETE',NOW());

INSERT INTO `gov_lc_run`
(`id`,`revision`,`status`,`ws`,`kind`,`table_fqn`,`batch_id`,`ds_task_id`,`dry_run`,`metrics_json`,`operator`,`started_at`,`finished_at`,`delete_flag`,`create_time`)
VALUES
('19300',1,'success','default','daily',NULL,'19200','ds-demo-lifecycle-19200',0,'{"steps":4,"status":"success"}','system',DATE_SUB(NOW(), INTERVAL 1 DAY),DATE_SUB(NOW(), INTERVAL 1 DAY),'NOT_DELETE',DATE_SUB(NOW(), INTERVAL 1 DAY));

INSERT INTO `gov_lc_orphan_scan`
(`id`,`revision`,`status`,`ws`,`bucket`,`candidate_count`,`bytes`,`window_ok`,`dry_run`,`result_uri`,`run_id`,`delete_flag`,`create_time`)
VALUES
('19401',1,'done','default','iceberg-ods',412,40802189312,1,1,'s3://lake-audit/orphan/ods-dryrun.json',NULL,'NOT_DELETE',NOW()),
('19402',1,'done','default','iceberg-dwd',186,19327352832,1,1,'s3://lake-audit/orphan/dwd-dryrun.json',NULL,'NOT_DELETE',NOW()),
('19403',1,'done','default','iceberg-dws',52,8589934592,0,1,'s3://lake-audit/orphan/dws-dryrun.json',NULL,'NOT_DELETE',NOW());
