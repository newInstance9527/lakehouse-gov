-- 即席：用户保存脚本（短正文进库；工程脚本归 develop Git）

CREATE TABLE IF NOT EXISTS `cp_query_saved` (
  `id`                varchar(20)   NOT NULL COMMENT '主键',
  `ws`                varchar(64)   NOT NULL DEFAULT 'default' COMMENT '工作空间',
  `user_id`           varchar(20)   DEFAULT NULL COMMENT '门户用户 id',
  `user_name`         varchar(64)   DEFAULT NULL COMMENT '展示名',
  `name`              varchar(256)  NOT NULL COMMENT '脚本名',
  `sql_text`          mediumtext    COMMENT 'SQL 正文（短文本）',
  `sql_hash`          varchar(64)   DEFAULT NULL COMMENT 'SQL sha256 前缀',
  `sql_summary`       varchar(512)  DEFAULT NULL COMMENT '摘要',
  `engine`            varchar(32)   NOT NULL DEFAULT 'trino' COMMENT '引擎',
  `status`            varchar(32)   NOT NULL DEFAULT 'active' COMMENT 'active/archived',
  `delete_flag`       varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`       datetime      DEFAULT NULL COMMENT '创建时间',
  `create_user`       varchar(20)   DEFAULT NULL COMMENT '创建用户',
  `update_time`       datetime      DEFAULT NULL COMMENT '修改时间',
  `update_user`       varchar(20)   DEFAULT NULL COMMENT '修改用户',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_cp_query_saved_user` (`user_id`,`update_time`) USING BTREE,
  KEY `idx_cp_query_saved_ws` (`ws`,`update_time`) USING BTREE,
  UNIQUE KEY `uq_cp_query_saved_user_name` (`user_id`,`ws`,`name`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='计算：即席保存脚本';
