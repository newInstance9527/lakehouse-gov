-- BI：Superset Dashboard 元数据回写门户「我的看板」

CREATE TABLE IF NOT EXISTS `gov_bi_dashboard` (
  `id`                     varchar(20)   NOT NULL COMMENT '主键',
  `revision`               int           NOT NULL DEFAULT 1,
  `status`                 varchar(32)   DEFAULT 'active',
  `ws`                     varchar(64)   DEFAULT 'default',
  `remark`                 varchar(512)  DEFAULT NULL,
  `superset_dashboard_id`  bigint        NOT NULL COMMENT 'Superset dashboard pk',
  `dashboard_title`        varchar(256)  DEFAULT NULL,
  `dashboard_slug`         varchar(128)  DEFAULT NULL,
  `url_path`               varchar(512)  DEFAULT NULL COMMENT '相对路径或深链',
  `published`              tinyint       DEFAULT 1 COMMENT 'Superset published',
  `pinned`                 tinyint       DEFAULT 0 COMMENT '门户置顶/我的看板',
  `metric_codes`           varchar(512)  DEFAULT NULL COMMENT '关联指标编码，逗号分隔（可选）',
  `filters_hint`           text          COMMENT '维筛选契约 JSON：filters.dims≡params.dims',
  `sync_state`             varchar(32)   DEFAULT 'synced' COMMENT 'synced/stale/revoked/error',
  `last_sync_at`           datetime      DEFAULT NULL,
  `last_error`             varchar(512)  DEFAULT NULL,
  `delete_flag`            varchar(32)   DEFAULT 'NOT_DELETE',
  `create_time`            datetime      DEFAULT NULL,
  `create_user`            varchar(20)   DEFAULT NULL,
  `update_time`            datetime      DEFAULT NULL,
  `update_user`            varchar(20)   DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_bi_dash_ss` (`superset_dashboard_id`) USING BTREE,
  KEY `idx_bi_dash_ws_pin` (`ws`, `pinned`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='BI 看板（Superset 回写）';
