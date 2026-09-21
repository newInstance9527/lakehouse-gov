-- 指标中心 P0：口径 SoT + 版本 / 依赖 / 编译缓存 / 物化登记 / 状态历史
-- 规格：doc/指标中心.md

CREATE TABLE IF NOT EXISTS `gov_metric` (
  `id`             varchar(20)  NOT NULL COMMENT '主键',
  `revision`       int          NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
  `status`         varchar(32)  DEFAULT 'draft' COMMENT 'draft/review/active/version_review/deprecated',
  `ws`             varchar(64)  DEFAULT 'default' COMMENT '工作空间',
  `remark`         varchar(512) DEFAULT NULL COMMENT '备注',
  `metric_code`    varchar(64)  NOT NULL COMMENT '指标编码 A-/M-/C-',
  `name`           varchar(128) NOT NULL COMMENT '名称',
  `kind`           varchar(16)  NOT NULL COMMENT '原子/衍生/复合',
  `domain_code`    varchar(64)  DEFAULT NULL COMMENT '业务域 trade/user/goods',
  `unit`           varchar(32)  DEFAULT NULL COMMENT '单位',
  `owner`          varchar(64)  DEFAULT NULL COMMENT '负责人',
  `current_ver`    varchar(16)  DEFAULT 'v1' COMMENT '当前版本号',
  `current_ver_id` varchar(20)  DEFAULT NULL COMMENT '当前 gov_metric_ver.id',
  `om_fqn`         varchar(512) DEFAULT NULL COMMENT 'OM挂接（非口径SoT）',
  `grav_asset_id`  varchar(20)  DEFAULT NULL COMMENT '主绑定湖表指针（原子）',
  `delete_flag`    varchar(32)  DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`    datetime     DEFAULT NULL COMMENT '创建时间',
  `create_user`    varchar(20)  DEFAULT NULL COMMENT '创建用户',
  `update_time`    datetime     DEFAULT NULL COMMENT '修改时间',
  `update_user`    varchar(20)  DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_gov_metric_code` (`ws`, `metric_code`) USING BTREE,
  KEY `idx_gov_metric_status` (`status`) USING BTREE,
  KEY `idx_gov_metric_domain` (`domain_code`) USING BTREE,
  KEY `idx_gov_metric_kind` (`kind`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='SoT：指标头';

CREATE TABLE IF NOT EXISTS `gov_metric_ver` (
  `id`              varchar(20)   NOT NULL COMMENT '主键',
  `revision`        int           NOT NULL DEFAULT 1 COMMENT '乐观锁',
  `status`          varchar(32)   DEFAULT 'active' COMMENT '版本行状态',
  `ws`              varchar(64)   DEFAULT 'default' COMMENT '工作空间',
  `remark`          varchar(512)  DEFAULT NULL COMMENT '备注',
  `metric_id`       varchar(20)   NOT NULL COMMENT 'gov_metric.id',
  `ver`             varchar(16)   NOT NULL COMMENT 'v1/v2…',
  `caliber`         text          COMMENT '业务口径说明',
  `formula`         text          COMMENT '复合公式（仅复合）',
  `atom_ref`        varchar(64)   DEFAULT NULL COMMENT '衍生依赖原子 code',
  `agg`             varchar(32)   DEFAULT NULL COMMENT '原子聚合',
  `bind_table`      varchar(256)  DEFAULT NULL COMMENT '原子绑定表',
  `bind_field`      varchar(128)  DEFAULT NULL COMMENT '原子绑定字段',
  `qualifier_json`  text          COMMENT '衍生业务限定 JSON 数组',
  `grain_json`      text          COMMENT '统计粒度 JSON 数组',
  `time_window`     varchar(64)   DEFAULT NULL COMMENT '统计周期',
  `fingerprint`     varchar(64)   DEFAULT NULL COMMENT '口径哈希',
  `pending_caliber` text          COMMENT 'version_review 待审口径',
  `delete_flag`     varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`     datetime      DEFAULT NULL COMMENT '创建时间',
  `create_user`     varchar(20)   DEFAULT NULL COMMENT '创建用户',
  `update_time`     datetime      DEFAULT NULL COMMENT '修改时间',
  `update_user`     varchar(20)   DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_gov_metric_ver` (`metric_id`, `ver`) USING BTREE,
  KEY `idx_gov_metric_ver_metric` (`metric_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='SoT：指标版本（不可变语义，草稿可覆盖写）';

CREATE TABLE IF NOT EXISTS `gov_metric_dep` (
  `id`         varchar(20)  NOT NULL COMMENT '主键',
  `ver_id`     varchar(20)  NOT NULL COMMENT 'gov_metric_ver.id',
  `dep_code`   varchar(64)  NOT NULL COMMENT '依赖指标编码',
  `dep_ver`    varchar(16)  DEFAULT NULL COMMENT '钉死依赖版本（可空）',
  `create_time` datetime    DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_metric_dep_ver` (`ver_id`) USING BTREE,
  KEY `idx_metric_dep_code` (`dep_code`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='指标依赖边';

CREATE TABLE IF NOT EXISTS `gov_metric_sql` (
  `id`           varchar(20)   NOT NULL COMMENT '主键',
  `ver_id`       varchar(20)   NOT NULL COMMENT 'gov_metric_ver.id',
  `dialect`      varchar(16)   NOT NULL COMMENT 'trino/clickhouse',
  `sql_text`     mediumtext    NOT NULL COMMENT '编译SQL',
  `dep_closure`  text          COMMENT '依赖闭包 JSON',
  `bind_assets`  text          COMMENT '触及表 FQN JSON',
  `create_time`  datetime      DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_metric_sql_dialect` (`ver_id`, `dialect`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='指标编译缓存';

CREATE TABLE IF NOT EXISTS `gov_metric_materialize` (
  `id`           varchar(20)   NOT NULL COMMENT '主键',
  `revision`     int           NOT NULL DEFAULT 1,
  `status`       varchar(32)   DEFAULT 'active',
  `ws`           varchar(64)   DEFAULT 'default',
  `remark`       varchar(512)  DEFAULT NULL,
  `metric_code`  varchar(64)   NOT NULL COMMENT '指标编码',
  `ver`          varchar(16)   NOT NULL COMMENT '版本',
  `engine`       varchar(32)   NOT NULL COMMENT 'iceberg/clickhouse',
  `target_table` varchar(256)  NOT NULL COMMENT '物化表',
  `grain_json`   text          COMMENT '物化粒度 JSON',
  `job_ref`      varchar(128)  DEFAULT NULL COMMENT 'DS/Spark作业引用',
  `recon_ok`     tinyint       DEFAULT 0 COMMENT '对账是否通过',
  `delete_flag`  varchar(32)   DEFAULT 'NOT_DELETE',
  `create_time`  datetime      DEFAULT NULL,
  `create_user`  varchar(20)   DEFAULT NULL,
  `update_time`  datetime      DEFAULT NULL,
  `update_user`  varchar(20)   DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_metric_mat_code` (`metric_code`, `ver`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='指标物化登记（P2）';

CREATE TABLE IF NOT EXISTS `gov_metric_history` (
  `id`          varchar(20)   NOT NULL COMMENT '主键',
  `metric_id`   varchar(20)   NOT NULL COMMENT 'gov_metric.id',
  `status`      varchar(32)   NOT NULL COMMENT '状态键',
  `label`       varchar(64)   DEFAULT NULL COMMENT '展示名',
  `note`        varchar(512)  DEFAULT NULL COMMENT '说明',
  `create_time` datetime      DEFAULT NULL COMMENT '发生时间',
  `create_user` varchar(20)   DEFAULT NULL COMMENT '操作人',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_metric_hist` (`metric_id`, `create_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='指标状态流转审计';

-- 演示种子（对齐前端 METRIC_CATALOG 样例）
INSERT INTO `gov_metric`
(`id`,`revision`,`status`,`ws`,`metric_code`,`name`,`kind`,`domain_code`,`unit`,`owner`,`current_ver`,`current_ver_id`,`delete_flag`,`create_time`)
VALUES
('gm01',1,'active','default','A-0012','支付成功订单数','原子','trade','个','李明','v1','gmv01','NOT_DELETE',NOW()),
('gm02',1,'active','default','A-0201','浏览商品 UV','原子','trade','人','周健','v1','gmv02','NOT_DELETE',NOW()),
('gm03',1,'active','default','M-0001','日GMV','衍生','trade','元','李明','v3','gmv03','NOT_DELETE',NOW()),
('gm04',1,'active','default','C-0035','客单价 AOV','复合','trade','元/单','李明','v1','gmv04','NOT_DELETE',NOW()),
('gm05',1,'draft','default','A-0301','退款订单数','原子','trade','个','孙悦','v1','gmv05','NOT_DELETE',NOW())
ON DUPLICATE KEY UPDATE `update_time`=NOW();

INSERT INTO `gov_metric_ver`
(`id`,`revision`,`status`,`ws`,`metric_id`,`ver`,`caliber`,`formula`,`atom_ref`,`agg`,`bind_table`,`bind_field`,`qualifier_json`,`grain_json`,`time_window`,`fingerprint`,`delete_flag`,`create_time`)
VALUES
('gmv01',1,'active','default','gm01','v1','pay_status=SUCCESS 去重计数',NULL,NULL,'COUNT DISTINCT','dwd_trade.dwd_order_detail','order_id',NULL,NULL,NULL,'seed-a0012','NOT_DELETE',NOW()),
('gmv02',1,'active','default','gm02','v1','商品详情页曝光 UV',NULL,NULL,'COUNT DISTINCT','dwd_log.dwd_log_action','user_id','["event_type=''page_view''"]',NULL,NULL,'seed-a0201','NOT_DELETE',NOW()),
('gmv03',1,'active','default','gm03','v3','订单支付成功金额合计，不含退款',NULL,'A-0012',NULL,NULL,NULL,'["pay_status=SUCCESS"]','["dt"]','近1天','seed-m0001','NOT_DELETE',NOW()),
('gmv04',1,'active','default','gm04','v1','客单价 = 日GMV / 支付成功订单数','M-0001 / A-0012',NULL,NULL,NULL,NULL,NULL,NULL,NULL,'seed-c0035','NOT_DELETE',NOW()),
('gmv05',1,'active','default','gm05','v1','退款完成订单去重',NULL,NULL,'COUNT DISTINCT','dwd_trade.dwd_order_detail','order_id','["refund_status=DONE"]',NULL,NULL,'seed-a0301','NOT_DELETE',NOW())
ON DUPLICATE KEY UPDATE `update_time`=NOW();

INSERT INTO `gov_metric_dep` (`id`,`ver_id`,`dep_code`,`dep_ver`,`create_time`)
VALUES
('gmd01','gmv03','A-0012','v1',NOW()),
('gmd02','gmv04','M-0001','v3',NOW()),
('gmd03','gmv04','A-0012','v1',NOW())
ON DUPLICATE KEY UPDATE `dep_code`=VALUES(`dep_code`);

INSERT INTO `gov_metric_history` (`id`,`metric_id`,`status`,`label`,`note`,`create_time`)
VALUES
('gmh01','gm01','active','已启用','v1 启用',NOW()),
('gmh02','gm03','active','已启用','v3 启用',NOW()),
('gmh03','gm04','active','已启用','v1 启用',NOW()),
('gmh04','gm05','draft','草稿','新建保存为草稿',NOW())
ON DUPLICATE KEY UPDATE `note`=VALUES(`note`);
