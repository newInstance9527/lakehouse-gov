-- 数据开发脚本索引 / 试跑 / 发布单 / UDF。正文在 Git，不在这些表里双写。

ALTER TABLE `gov_ws`
  ADD COLUMN `git_remote_url` varchar(512) DEFAULT NULL COMMENT '工作空间脚本 Git 远程（空则只用门户本地仓库）';

CREATE TABLE IF NOT EXISTS `cp_script_index` (
  `id`           varchar(20)  NOT NULL COMMENT '主键',
  `ws`           varchar(64)  NOT NULL DEFAULT 'default' COMMENT '工作空间',
  `path`         varchar(512) NOT NULL COMMENT 'Git 相对路径 scripts/..sql',
  `name`         varchar(256) NOT NULL COMMENT '文件名',
  `folder`       varchar(256) DEFAULT NULL COMMENT '资源树文件夹',
  `engine`       varchar(32)  NOT NULL DEFAULT 'spark' COMMENT 'spark/flink/trino',
  `env`          varchar(16)  NOT NULL DEFAULT 'TEST' COMMENT 'TEST/PRE，禁止 PROD',
  `status`       varchar(32)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/READY/IN_REVIEW/PUBLISHED/FAILED',
  `git_sha`      varchar(64)  DEFAULT NULL COMMENT '最近提交',
  `author_name`  varchar(64)  DEFAULT NULL COMMENT '作者展示名',
  `lint_json`    varchar(2000) DEFAULT NULL COMMENT '静态检查 JSON',
  `delete_flag`  varchar(32)  DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`  datetime     DEFAULT NULL COMMENT '创建时间',
  `create_user`  varchar(20)  DEFAULT NULL COMMENT '创建用户',
  `update_time`  datetime     DEFAULT NULL COMMENT '修改时间',
  `update_user`  varchar(20)  DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_cp_script_ws_path` (`ws`,`path`) USING BTREE,
  KEY `idx_cp_script_ws` (`ws`,`update_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='计算：开发脚本索引（正文在 Git）';

CREATE TABLE IF NOT EXISTS `cp_script_run` (
  `id`               varchar(20)  NOT NULL COMMENT '主键',
  `run_id`           varchar(64)  NOT NULL COMMENT '试跑号',
  `script_id`        varchar(20)  NOT NULL COMMENT '脚本索引 id',
  `ws`               varchar(64)  NOT NULL DEFAULT 'default' COMMENT '工作空间',
  `engine`           varchar(32)  NOT NULL COMMENT '引擎',
  `env`              varchar(16)  NOT NULL COMMENT 'TEST/PRE',
  `status`           varchar(32)  NOT NULL DEFAULT 'submitted' COMMENT 'submitted/running/ok/failed',
  `message`          varchar(1024) DEFAULT NULL COMMENT '结果摘要',
  `log_uri`          varchar(512) DEFAULT NULL COMMENT '日志或调度实例地址',
  `ds_workflow_code` varchar(64)  DEFAULT NULL COMMENT 'DS 流程 code',
  `ds_instance_id`   varchar(64)  DEFAULT NULL COMMENT 'DS 实例 id',
  `git_sha`          varchar(64)  DEFAULT NULL COMMENT '试跑时的提交',
  `row_count`        int          DEFAULT NULL COMMENT 'Trino 校验返回行数',
  `dur_ms`           bigint       DEFAULT NULL COMMENT '耗时',
  `delete_flag`      varchar(32)  DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`      datetime     DEFAULT NULL COMMENT '创建时间',
  `create_user`      varchar(20)  DEFAULT NULL COMMENT '创建用户',
  `update_time`      datetime     DEFAULT NULL COMMENT '修改时间',
  `update_user`      varchar(20)  DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_cp_script_run_id` (`run_id`) USING BTREE,
  KEY `idx_cp_script_run_script` (`script_id`,`create_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='计算：开发脚本试跑';

CREATE TABLE IF NOT EXISTS `cp_release` (
  `id`               varchar(20)  NOT NULL COMMENT '主键',
  `ws`               varchar(64)  NOT NULL DEFAULT 'default' COMMENT '工作空间',
  `script_id`        varchar(20)  NOT NULL COMMENT '脚本索引 id',
  `pkg`              varchar(128) NOT NULL COMMENT '发布包名',
  `script_name`      varchar(256) DEFAULT NULL COMMENT '脚本文件名',
  `script_path`      varchar(512) DEFAULT NULL COMMENT 'Git 路径',
  `engine`           varchar(32)  NOT NULL COMMENT '引擎',
  `env`              varchar(16)  NOT NULL COMMENT '目标环境 dev/stg',
  `git_sha`          varchar(64)  DEFAULT NULL COMMENT '提交',
  `git_tag`          varchar(128) DEFAULT NULL COMMENT '发布 tag',
  `status`           varchar(32)  NOT NULL DEFAULT 'IN_REVIEW' COMMENT 'IN_REVIEW/PUBLISHED/REJECTED/ROLLED_BACK',
  `result_label`     varchar(32)  NOT NULL DEFAULT '门禁中' COMMENT '门禁中/成功/回滚/未通过',
  `gates_json`       mediumtext   COMMENT '门禁快照',
  `ds_workflow_code` varchar(64)  DEFAULT NULL COMMENT '生产投影流程 code',
  `rolled_to_tag`    varchar(128) DEFAULT NULL COMMENT '回滚指向的 tag',
  `delete_flag`      varchar(32)  DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`      datetime     DEFAULT NULL COMMENT '创建时间',
  `create_user`      varchar(20)  DEFAULT NULL COMMENT '创建用户',
  `update_time`      datetime     DEFAULT NULL COMMENT '修改时间',
  `update_user`      varchar(20)  DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_cp_release_ws` (`ws`,`create_time`) USING BTREE,
  KEY `idx_cp_release_script` (`script_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='计算：脚本发布单';

CREATE TABLE IF NOT EXISTS `cp_udf` (
  `id`           varchar(20)  NOT NULL COMMENT '主键',
  `name`         varchar(128) NOT NULL COMMENT '函数名',
  `description`  varchar(512) DEFAULT NULL COMMENT '说明',
  `engines`      varchar(128) NOT NULL COMMENT '逗号分隔 spark,flink,trino',
  `engine_label` varchar(64)  DEFAULT NULL COMMENT '展示用引擎串',
  `ver`          varchar(32)  DEFAULT NULL COMMENT '版本',
  `uses_text`    varchar(256) DEFAULT NULL COMMENT '使用处',
  `snippet`      varchar(512) NOT NULL COMMENT '插入片段',
  `status`       varchar(32)  NOT NULL DEFAULT 'ENABLE' COMMENT 'ENABLE',
  `delete_flag`  varchar(32)  DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`  datetime     DEFAULT NULL COMMENT '创建时间',
  `create_user`  varchar(20)  DEFAULT NULL COMMENT '创建用户',
  `update_time`  datetime     DEFAULT NULL COMMENT '修改时间',
  `update_user`  varchar(20)  DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_cp_udf_name` (`name`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='计算：UDF 登记';

INSERT INTO `cp_udf` (`id`,`name`,`description`,`engines`,`engine_label`,`ver`,`uses_text`,`snippet`,`status`,`delete_flag`,`create_time`)
SELECT 'udf000000000000001','udf_mask_phone','手机号动态脱敏·保留前3后4','spark,flink,trino','Spark+Flink+Trino','v3','安全模块·dwd_user_info','udf_mask_phone(buyer_mobile)','ENABLE','NOT_DELETE',NOW()
WHERE NOT EXISTS (SELECT 1 FROM `cp_udf` WHERE `name`='udf_mask_phone');
INSERT INTO `cp_udf` (`id`,`name`,`description`,`engines`,`engine_label`,`ver`,`uses_text`,`snippet`,`status`,`delete_flag`,`create_time`)
SELECT 'udf000000000000002','udf_mask_id_card','身份证号脱敏·保留前6后4','spark,trino','Spark+Trino','v2','dwd_user_info','udf_mask_id_card(id_card)','ENABLE','NOT_DELETE',NOW()
WHERE NOT EXISTS (SELECT 1 FROM `cp_udf` WHERE `name`='udf_mask_id_card');
INSERT INTO `cp_udf` (`id`,`name`,`description`,`engines`,`engine_label`,`ver`,`uses_text`,`snippet`,`status`,`delete_flag`,`create_time`)
SELECT 'udf000000000000003','map_status_code','交易状态码值标准化映射','spark,flink,trino','Spark+Flink+Trino','v5','dwd_order_detail','map_status_code(order_status)','ENABLE','NOT_DELETE',NOW()
WHERE NOT EXISTS (SELECT 1 FROM `cp_udf` WHERE `name`='map_status_code');
INSERT INTO `cp_udf` (`id`,`name`,`description`,`engines`,`engine_label`,`ver`,`uses_text`,`snippet`,`status`,`delete_flag`,`create_time`)
SELECT 'udf000000000000004','udf_parse_json','JSON 字段解析提取','spark,flink,trino','Spark+Flink+Trino','v1','埋点 dws_pv','udf_parse_json(payload, ''$.event'')','ENABLE','NOT_DELETE',NOW()
WHERE NOT EXISTS (SELECT 1 FROM `cp_udf` WHERE `name`='udf_parse_json');
INSERT INTO `cp_udf` (`id`,`name`,`description`,`engines`,`engine_label`,`ver`,`uses_text`,`snippet`,`status`,`delete_flag`,`create_time`)
SELECT 'udf000000000000005','udf_geo_hash','经纬度 Geohash 编码','spark,trino','Spark+Trino','v2','用户画像','udf_geo_hash(lat, lng, 6)','ENABLE','NOT_DELETE',NOW()
WHERE NOT EXISTS (SELECT 1 FROM `cp_udf` WHERE `name`='udf_geo_hash');
INSERT INTO `cp_udf` (`id`,`name`,`description`,`engines`,`engine_label`,`ver`,`uses_text`,`snippet`,`status`,`delete_flag`,`create_time`)
SELECT 'udf000000000000006','udf_date_key','日期转数字键 yyyyMMdd','spark,flink,trino','Spark+Flink+Trino','v1','通用','udf_date_key(dt)','ENABLE','NOT_DELETE',NOW()
WHERE NOT EXISTS (SELECT 1 FROM `cp_udf` WHERE `name`='udf_date_key');
