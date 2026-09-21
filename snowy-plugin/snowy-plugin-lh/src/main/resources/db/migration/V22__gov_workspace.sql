-- 工作空间：组织归属 / 成本 / 协作上下文（非 Catalog 硬隔离）
-- 规格：doc/工作空间.md

CREATE TABLE IF NOT EXISTS `gov_ws` (
  `id`                 varchar(20)   NOT NULL COMMENT '主键',
  `revision`           int           NOT NULL DEFAULT 1 COMMENT '乐观锁',
  `status`             varchar(32)   DEFAULT 'active' COMMENT 'active/archived',
  `ws`                 varchar(64)   DEFAULT 'default' COMMENT '登记归属（通常=ws_code）',
  `remark`             varchar(512)  DEFAULT NULL COMMENT '备注',
  `ws_code`            varchar(64)   NOT NULL COMMENT '空间编码（对外）',
  `name`               varchar(128)  NOT NULL COMMENT '展示名',
  `icon`               varchar(16)   DEFAULT NULL COMMENT 'emoji/图标',
  `domain_code`        varchar(64)   DEFAULT NULL COMMENT '业务域',
  `cost_center`        varchar(64)   DEFAULT NULL COMMENT '成本中心',
  `trino_rg`           varchar(64)   DEFAULT NULL COMMENT 'Trino 资源组',
  `preferred_schemas`  varchar(256)  DEFAULT NULL COMMENT '常用 schema 提示（非隔离）',
  `owners`             varchar(256)  DEFAULT NULL COMMENT 'Owner 展示名汇总',
  `detail`             varchar(1024) DEFAULT NULL COMMENT '描述',
  `tags_json`          varchar(512)  DEFAULT NULL COMMENT '标签 JSON [{text,cls}]',
  `delete_flag`        varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`        datetime      DEFAULT NULL,
  `create_user`        varchar(20)   DEFAULT NULL,
  `update_time`        datetime      DEFAULT NULL,
  `update_user`        varchar(20)   DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_gov_ws_code` (`ws_code`) USING BTREE,
  KEY `idx_gov_ws_status` (`status`) USING BTREE,
  KEY `idx_gov_ws_domain` (`domain_code`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='工作空间主数据（归属/成本/协作）';

CREATE TABLE IF NOT EXISTS `gov_ws_member` (
  `id`            varchar(20)  NOT NULL COMMENT '主键',
  `revision`      int          NOT NULL DEFAULT 1,
  `status`        varchar(32)  DEFAULT 'active' COMMENT 'active/disabled',
  `ws`            varchar(64)  DEFAULT 'default' COMMENT '冗余=ws_code',
  `remark`        varchar(512) DEFAULT NULL,
  `ws_code`       varchar(64)  NOT NULL COMMENT 'gov_ws.ws_code',
  `subject_type`  varchar(16)  NOT NULL DEFAULT 'user' COMMENT 'user/sa/group',
  `subject_id`    varchar(64)  NOT NULL COMMENT '用户ID/账号/SA名',
  `display_name`  varchar(128) DEFAULT NULL COMMENT '展示名',
  `role_code`     varchar(32)  NOT NULL COMMENT 'Owner/Developer/Operator/BusinessUser/SecurityOfficer/ServiceAccount',
  `scope_note`    varchar(256) DEFAULT NULL COMMENT '门户职责说明（≠引擎ACL）',
  `last_login`    datetime     DEFAULT NULL COMMENT '最近登录（展示）',
  `delete_flag`   varchar(32)  DEFAULT 'NOT_DELETE',
  `create_time`   datetime     DEFAULT NULL,
  `create_user`   varchar(20)  DEFAULT NULL,
  `update_time`   datetime     DEFAULT NULL,
  `update_user`   varchar(20)  DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_gov_ws_member` (`ws_code`, `subject_type`, `subject_id`) USING BTREE,
  KEY `idx_gov_ws_member_ws` (`ws_code`) USING BTREE,
  KEY `idx_gov_ws_member_subject` (`subject_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='工作空间成员（门户协作角色）';

CREATE TABLE IF NOT EXISTS `gov_ws_quota` (
  `id`              varchar(20)    NOT NULL COMMENT '主键',
  `revision`        int            NOT NULL DEFAULT 1,
  `status`          varchar(32)    DEFAULT 'ok' COMMENT 'ok/warn',
  `ws`              varchar(64)    DEFAULT 'default',
  `remark`          varchar(512)   DEFAULT NULL,
  `ws_code`         varchar(64)    NOT NULL COMMENT 'gov_ws.ws_code',
  `storage_quota_tb` decimal(12,2) NOT NULL DEFAULT 1 COMMENT '存储上限 TB',
  `storage_used_tb`  decimal(12,2) NOT NULL DEFAULT 0 COMMENT '存储已用 TB（快照）',
  `cu_quota`         int           NOT NULL DEFAULT 200 COMMENT 'CU 日上限',
  `cu_used`          int           NOT NULL DEFAULT 0 COMMENT 'CU 已用快照',
  `trino_quota`      int           NOT NULL DEFAULT 5 COMMENT 'Trino 并发上限',
  `trino_used`       int           NOT NULL DEFAULT 0,
  `api_qps_quota`    int           NOT NULL DEFAULT 100 COMMENT 'API QPS 上限',
  `api_qps_used`     int           NOT NULL DEFAULT 0,
  `delete_flag`      varchar(32)   DEFAULT 'NOT_DELETE',
  `create_time`      datetime      DEFAULT NULL,
  `create_user`      varchar(20)   DEFAULT NULL,
  `update_time`      datetime      DEFAULT NULL,
  `update_user`      varchar(20)   DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_gov_ws_quota` (`ws_code`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='工作空间配额与用量快照';

CREATE TABLE IF NOT EXISTS `gov_ws_user_pref` (
  `id`              varchar(20)  NOT NULL COMMENT '主键',
  `user_id`         varchar(20)  NOT NULL COMMENT '用户ID',
  `current_ws_code` varchar(64)  NOT NULL DEFAULT 'default' COMMENT '当前协作上下文',
  `update_time`     datetime     DEFAULT NULL,
  `create_time`     datetime     DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_gov_ws_user_pref` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户当前工作空间偏好';

-- ========== 种子：共享 Catalog 下的团队归属 ==========
INSERT INTO `gov_ws` (`id`,`revision`,`status`,`ws`,`ws_code`,`name`,`icon`,`domain_code`,`cost_center`,`trino_rg`,`preferred_schemas`,`owners`,`detail`,`tags_json`,`delete_flag`,`create_time`)
VALUES
('1908902222000000001',1,'active','default','default','平台默认','🗂️','平台','CC-DEFAULT','rg_default','—','平台','全局默认归属；未指定 ws 的业务数据落此。','[{"text":"默认","cls":"tag-gray"}]','NOT_DELETE',NOW()),
('1908902222000000002',1,'active','ws_trade','ws_trade','交易域团队','🛒','交易','CC-TRADE-01','rg_trade','ods_trade / dwd_trade / ads','李明 · 张涛','交易域组织归属空间：资产/任务成本记在本团队。目录仍可全局发现；成员读数须走申请中心。','[{"text":"含敏感认责","cls":"tag-orange"},{"text":"核心域","cls":"tag-blue"}]','NOT_DELETE',NOW()),
('1908902222000000003',1,'active','ws_user','ws_user','用户域团队','👤','用户','CC-USER-01','rg_user','ods_user / dwd_user / ads','王欢','用户域归属与成本空间。明文 PII 须安全岗参与审批。','[{"text":"含 PII 认责","cls":"tag-orange"},{"text":"明文需申请","cls":"tag-purple"}]','NOT_DELETE',NOW()),
('1908902222000000004',1,'active','ws_goods','ws_goods','商品域团队','📦','商品','CC-GOODS-01','rg_goods','dim / dwd_goods','赵静','商品与库存维度归属；dim_sku 黄金资产鼓励复用。','[{"text":"维度域","cls":"tag-blue"},{"text":"鼓励复用","cls":"tag-green"}]','NOT_DELETE',NOW()),
('1908902222000000005',1,'active','ws_marketing','ws_marketing','营销域团队','📢','营销','CC-MKT-01','rg_mkt','ads','张涛','营销分析归属：多申请读交易域成果，少平行再建。','[{"text":"跨域消费","cls":"tag-gray"},{"text":"配额告警","cls":"tag-orange"}]','NOT_DELETE',NOW()),
('1908902222000000006',1,'active','ws_finance','ws_finance','财务域团队','💰','财务','CC-FIN-01','rg_fin','ads_fin','刘强 · 安全岗','财务归属空间：审批链默认含安全岗。共享 Catalog，入空间不增引擎权限。','[{"text":"高敏感认责","cls":"tag-red"},{"text":"强审计","cls":"tag-orange"}]','NOT_DELETE',NOW()),
('1908902222000000007',1,'active','ws_sandbox','ws_sandbox','实验沙箱团队','🧪','实验','CC-SBX-01','rg_sbx','tmp_sbx','陈晓','沙箱归属与成本记账；写操作仍限技术前缀 + 作业 SA。','[{"text":"TTL 清理","cls":"tag-orange"},{"text":"低配额","cls":"tag-gray"}]','NOT_DELETE',NOW());

INSERT INTO `gov_ws_quota` (`id`,`revision`,`status`,`ws`,`ws_code`,`storage_quota_tb`,`storage_used_tb`,`cu_quota`,`cu_used`,`trino_quota`,`trino_used`,`api_qps_quota`,`api_qps_used`,`delete_flag`,`create_time`)
VALUES
('1908902222000000101',1,'ok','default','default',1,0.05,200,10,3,0,100,10,'NOT_DELETE',NOW()),
('1908902222000000102',1,'ok','ws_trade','ws_trade',8,1.8,2000,1200,20,12,2000,800,'NOT_DELETE',NOW()),
('1908902222000000103',1,'ok','ws_user','ws_user',4,0.9,800,300,10,6,1000,300,'NOT_DELETE',NOW()),
('1908902222000000104',1,'ok','ws_goods','ws_goods',3,0.6,600,180,8,4,800,200,'NOT_DELETE',NOW()),
('1908902222000000105',1,'warn','ws_marketing','ws_marketing',2,1.7,400,80,5,3,600,500,'NOT_DELETE',NOW()),
('1908902222000000106',1,'ok','ws_finance','ws_finance',2,0.3,300,40,5,2,400,100,'NOT_DELETE',NOW()),
('1908902222000000107',1,'ok','ws_sandbox','ws_sandbox',1,0.1,200,0,3,1,100,50,'NOT_DELETE',NOW());

INSERT INTO `gov_ws_member` (`id`,`revision`,`status`,`ws`,`ws_code`,`subject_type`,`subject_id`,`display_name`,`role_code`,`scope_note`,`last_login`,`delete_flag`,`create_time`)
VALUES
('1908902222000000201',1,'active','ws_trade','ws_trade','user','lm','李明 (LM)','Owner','认责 · 默认审批人',NOW(),'NOT_DELETE',NOW()),
('1908902222000000202',1,'active','ws_trade','ws_trade','user','zt','张涛 (ZT)','Owner','认责 · 默认审批人',NOW(),'NOT_DELETE',NOW()),
('1908902222000000203',1,'active','ws_trade','ws_trade','user','wf','王芳 (WF)','Developer','可登记/开发 · 读数需申请',NOW(),'NOT_DELETE',NOW()),
('1908902222000000204',1,'active','ws_trade','ws_trade','user','cl','陈磊 (CL)','Developer','可登记/开发 · 读数需申请',NOW(),'NOT_DELETE',NOW()),
('1908902222000000205',1,'active','ws_trade','ws_trade','user','zm','赵敏 (ZM)','BusinessUser','消费方 · 读 ADS 需申请',NOW(),'NOT_DELETE',NOW()),
('1908902222000000206',1,'active','ws_trade','ws_trade','sa','svc-dolphin','svc-dolphin (SA)','ServiceAccount','作业身份 · ACL 在 Grav',NOW(),'NOT_DELETE',NOW()),
('1908902222000000211',1,'active','ws_user','ws_user','user','wh','王欢 (WH)','Owner','认责 · 默认审批人',NOW(),'NOT_DELETE',NOW()),
('1908902222000000212',1,'active','ws_user','ws_user','user','ln','李娜 (LN)','Developer','可登记/开发 · PII 明文需申请',NOW(),'NOT_DELETE',NOW()),
('1908902222000000213',1,'active','ws_user','ws_user','user','zj','周杰 (ZJ)','BusinessUser','消费方 · 读数需申请',NOW(),'NOT_DELETE',NOW()),
('1908902222000000221',1,'active','ws_goods','ws_goods','user','zjing','赵静 (ZJ)','Owner','认责 · 默认审批人',NOW(),'NOT_DELETE',NOW()),
('1908902222000000222',1,'active','ws_goods','ws_goods','user','sq','孙强 (SQ)','Developer','可登记/开发 · 读数需申请',NOW(),'NOT_DELETE',NOW()),
('1908902222000000231',1,'active','ws_marketing','ws_marketing','user','zt','张涛 (ZT)','Owner','认责 · 跨域申请默认审批',NOW(),'NOT_DELETE',NOW()),
('1908902222000000232',1,'active','ws_marketing','ws_marketing','user','hl','何莉 (HL)','BusinessUser','看板消费 · 读数需申请',NOW(),'NOT_DELETE',NOW()),
('1908902222000000241',1,'active','ws_finance','ws_finance','user','lq','刘强 (LQ)','Owner','认责 · 强审计审批',NOW(),'NOT_DELETE',NOW()),
('1908902222000000242',1,'active','ws_finance','ws_finance','user','sec','安全岗 (SEC)','SecurityOfficer','高敏感审批参与方',NOW(),'NOT_DELETE',NOW()),
('1908902222000000251',1,'active','ws_sandbox','ws_sandbox','user','cx','陈晓 (CX)','Owner','认责 · 沙箱配额',NOW(),'NOT_DELETE',NOW());
