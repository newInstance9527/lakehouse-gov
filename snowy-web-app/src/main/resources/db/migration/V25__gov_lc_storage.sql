-- 存储趋势分册 P0：非时序建议闭环 + 变更点标注
-- 规格：doc/存储趋势.md §5.3；时序走 VictoriaMetrics（P1 采集），本迁移不落日表

CREATE TABLE IF NOT EXISTS `gov_lc_storage_advice` (
  `id`                  varchar(20)   NOT NULL COMMENT '主键',
  `revision`            int           NOT NULL DEFAULT 1 COMMENT '乐观锁',
  `status`              varchar(32)   DEFAULT 'open' COMMENT 'open/linked/done/ignored',
  `ws`                  varchar(64)   DEFAULT 'default' COMMENT '工作空间',
  `remark`              varchar(512)  DEFAULT NULL COMMENT '备注',
  `dt`                  date          NOT NULL COMMENT '建议所属日期',
  `fqtn`                varchar(512)  DEFAULT NULL COMMENT '表 FQN；桶级建议可空',
  `kind`                varchar(32)   NOT NULL COMMENT 'compact/expire/orphan/archive/collect_fail',
  `priority`            int           DEFAULT 2 COMMENT '1=P1 高 / 2=P2 / 3=P3',
  `est_reclaim_bytes`   bigint        DEFAULT 0 COMMENT '预计可回收字节',
  `confidence`          varchar(16)   DEFAULT 'medium' COMMENT 'high/medium/low',
  `reason`              varchar(1024) DEFAULT NULL COMMENT '归因说明',
  `linked_run_id`       varchar(20)   DEFAULT NULL COMMENT '关联 gov_lc_run.id',
  `delete_flag`         varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`         datetime      DEFAULT NULL COMMENT '创建时间',
  `create_user`         varchar(20)   DEFAULT NULL COMMENT '创建用户',
  `update_time`         datetime      DEFAULT NULL COMMENT '修改时间',
  `update_user`         varchar(20)   DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_gov_lc_st_adv_ws_dt` (`ws`, `dt`, `status`) USING BTREE,
  KEY `idx_gov_lc_st_adv_fqtn` (`fqtn`) USING BTREE,
  KEY `idx_gov_lc_st_adv_reclaim` (`est_reclaim_bytes`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='存储趋势治理建议（非时序闭环）';

CREATE TABLE IF NOT EXISTS `gov_lc_storage_change_point` (
  `id`            varchar(20)   NOT NULL COMMENT '主键',
  `revision`      int           NOT NULL DEFAULT 1 COMMENT '乐观锁',
  `status`        varchar(32)   DEFAULT 'active' COMMENT 'active/cleared',
  `ws`            varchar(64)   DEFAULT 'default' COMMENT '工作空间',
  `remark`        varchar(512)  DEFAULT NULL COMMENT '备注',
  `dt`            date          NOT NULL COMMENT '变更点日期',
  `scope_type`    varchar(16)   NOT NULL COMMENT 'bucket/ws/total/table',
  `scope_key`     varchar(128)  NOT NULL COMMENT '作用域键',
  `kind`          varchar(32)   NOT NULL COMMENT 'policy/bulk_load/compaction_on',
  `note`          varchar(512)  DEFAULT NULL COMMENT '说明',
  `source_ref`    varchar(128)  DEFAULT NULL COMMENT '来源引用（策略 id / 作业 id）',
  `delete_flag`   varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`   datetime      DEFAULT NULL COMMENT '创建时间',
  `create_user`   varchar(20)   DEFAULT NULL COMMENT '创建用户',
  `update_time`   datetime      DEFAULT NULL COMMENT '修改时间',
  `update_user`   varchar(20)   DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_gov_lc_st_cp_ws_dt` (`ws`, `dt`) USING BTREE,
  KEY `idx_gov_lc_st_cp_scope` (`scope_type`, `scope_key`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='存储趋势变更点标注（分段回归截断）';

-- 种子：对齐演示 ST_ADVICE / 异常表
INSERT INTO `gov_lc_storage_advice`
(`id`,`revision`,`status`,`ws`,`dt`,`fqtn`,`kind`,`priority`,`est_reclaim_bytes`,`confidence`,`reason`,`linked_run_id`,`delete_flag`,`create_time`)
VALUES
('24001',1,'open','default',CURDATE(),'dwd_log_action','compact',1,98784247808,'high','近 7 日 +8.5%，文件数 890 · 目标 256MB compaction',NULL,'NOT_DELETE',NOW()),
('24002',1,'open','default',CURDATE(),'ods_trade.s_order','expire',1,98956046417,'high','保留策略 keepDays=3 · 快照膨胀推高 MinIO 水位',NULL,'NOT_DELETE',NOW()),
('24003',1,'open','default',CURDATE(),NULL,'archive',2,96636764160,'medium','38 个分区归档候选 · 预计回收 ~90 GB',NULL,'NOT_DELETE',NOW());

INSERT INTO `gov_lc_storage_change_point`
(`id`,`revision`,`status`,`ws`,`dt`,`scope_type`,`scope_key`,`kind`,`note`,`source_ref`,`delete_flag`,`create_time`)
VALUES
('24101',1,'active','default',DATE_SUB(CURDATE(), INTERVAL 12 DAY),'table','ods_trade.s_order','policy','keep_days 7→3','19001','NOT_DELETE',NOW()),
('24102',1,'active','default',DATE_SUB(CURDATE(), INTERVAL 5 DAY),'ws','default','bulk_load','交易域补数导入','ds-backfill-trade','NOT_DELETE',NOW());
