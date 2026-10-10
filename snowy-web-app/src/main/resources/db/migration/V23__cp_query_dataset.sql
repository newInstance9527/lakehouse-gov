-- 即席查询：保存数据集（抽样元数据；禁止全量落库）

CREATE TABLE IF NOT EXISTS `cp_query_dataset` (
  `id`                varchar(20)   NOT NULL COMMENT '主键',
  `ds_code`           varchar(64)   NOT NULL COMMENT '对外编码',
  `name`              varchar(256)  NOT NULL COMMENT '展示名',
  `ws`                varchar(64)   NOT NULL DEFAULT 'default' COMMENT '工作空间',
  `user_id`           varchar(20)   DEFAULT NULL COMMENT '创建人',
  `user_name`         varchar(64)   DEFAULT NULL COMMENT '创建人展示名',
  `query_id`          varchar(64)   DEFAULT NULL COMMENT '来源 cp_query_exec.query_id',
  `sql_text`          mediumtext    COMMENT '生成时 SQL',
  `sql_hash`          varchar(64)   DEFAULT NULL COMMENT 'SQL hash',
  `columns_json`      mediumtext    COMMENT '列元数据 JSON',
  `sample_json`       mediumtext    COMMENT '抽样行 JSON（≤200 行）',
  `row_count`         int           DEFAULT NULL COMMENT '抽样行数',
  `scan_bytes`        bigint        DEFAULT NULL COMMENT '来源扫描字节',
  `status`            varchar(32)   NOT NULL DEFAULT 'active' COMMENT 'active/archived',
  `delete_flag`       varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`       datetime      DEFAULT NULL COMMENT '创建时间',
  `create_user`       varchar(20)   DEFAULT NULL COMMENT '创建用户',
  `update_time`       datetime      DEFAULT NULL COMMENT '修改时间',
  `update_user`       varchar(20)   DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_cp_query_dataset_code` (`ws`,`ds_code`) USING BTREE,
  KEY `idx_cp_query_dataset_user` (`user_id`,`create_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='计算：即席结果数据集（抽样）';
