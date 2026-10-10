-- J1 / A7：出湖出库审计正式落库（门户 SoT；Grav 侧属性标记 soft-fail）

CREATE TABLE IF NOT EXISTS `gov_export_audit` (
  `id`              varchar(20)   NOT NULL COMMENT '主键',
  `ws`              varchar(64)   NOT NULL DEFAULT 'default' COMMENT '工作空间',
  `ticket_id`       varchar(20)   DEFAULT NULL COMMENT 'apply_ticket.id',
  `ticket_no`       varchar(64)   NOT NULL COMMENT 'EXP 单号',
  `event_type`      varchar(32)   NOT NULL COMMENT 'approved/job_linked/expire_stop/notice_purge',
  `src_table`       varchar(256)  DEFAULT NULL COMMENT '源表',
  `target`          varchar(256)  DEFAULT NULL COMMENT '回流目标',
  `purpose`         varchar(512)  DEFAULT NULL COMMENT '用途',
  `approver`        varchar(64)   DEFAULT NULL COMMENT '审批人/操作人',
  `dag_id`          varchar(20)   DEFAULT NULL COMMENT 'ig_etl_dag.id',
  `dag_code`        varchar(128)  DEFAULT NULL COMMENT 'DAG 编码',
  `node_key`        varchar(128)  DEFAULT NULL COMMENT 'sink 节点',
  `grav_ok`         tinyint(1)    DEFAULT 0 COMMENT 'Gravitino 属性标记是否成功',
  `grav_ref`        varchar(256)  DEFAULT NULL COMMENT 'Grav 坐标或策略摘要',
  `grav_message`    varchar(512)  DEFAULT NULL COMMENT 'Grav soft-fail 信息',
  `detail_json`     mediumtext    COMMENT '扩展 JSON',
  `event_time`      datetime      NOT NULL COMMENT '事件时间',
  `delete_flag`     varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`     datetime      DEFAULT NULL COMMENT '创建时间',
  `create_user`     varchar(20)   DEFAULT NULL COMMENT '创建用户',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_export_audit_ws_time` (`ws`, `event_time`) USING BTREE,
  KEY `idx_export_audit_ticket` (`ticket_no`, `event_time`) USING BTREE,
  KEY `idx_export_audit_type` (`event_type`, `event_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='出湖：出库审计（A7 正式落库）';
