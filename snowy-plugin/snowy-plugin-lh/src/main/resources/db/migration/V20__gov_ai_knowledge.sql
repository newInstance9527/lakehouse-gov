-- AI 助手 / 模型管理 / 知识库 P0
-- 规格：doc/AI助手.md · doc/AI模型管理.md · doc/知识库.md · doc/AI平台能力-跨模块待办.md

-- ========== 模型管理 ==========
CREATE TABLE IF NOT EXISTS `gov_ai_model` (
  `id`              varchar(20)   NOT NULL COMMENT '主键',
  `revision`        int           NOT NULL DEFAULT 1 COMMENT '乐观锁',
  `status`          varchar(32)   DEFAULT 'ok' COMMENT 'ok/warn/off',
  `ws`              varchar(64)   DEFAULT 'default' COMMENT '工作空间（登记归属）',
  `remark`          varchar(512)  DEFAULT NULL COMMENT '备注',
  `name`            varchar(128)  NOT NULL COMMENT '展示名',
  `vendor`          varchar(64)   NOT NULL COMMENT '厂商',
  `kind`            varchar(16)   NOT NULL DEFAULT 'chat' COMMENT 'chat/embed',
  `model_name`      varchar(128)  NOT NULL COMMENT '上游模型名',
  `base_url`        varchar(512)  NOT NULL COMMENT 'OpenAI兼容 base',
  `vault_path`      varchar(256)  DEFAULT NULL COMMENT 'Key 路径',
  `key_mask`        varchar(64)   DEFAULT NULL COMMENT '脱敏展示',
  `context_tokens`  varchar(32)   DEFAULT '128K' COMMENT '上下文窗展示',
  `price_unit`      varchar(16)   DEFAULT 'usd_1m' COMMENT 'usd_1m/cny_1k/cny_1m/free',
  `input_rate`      decimal(18,6) DEFAULT 0 COMMENT '输入单价',
  `output_rate`     decimal(18,6) DEFAULT 0 COMMENT '输出单价',
  `enabled`         tinyint       NOT NULL DEFAULT 1 COMMENT '是否启用',
  `latency_ms`      int           DEFAULT NULL COMMENT '最近探测延迟',
  `role_label`      varchar(128)  DEFAULT NULL COMMENT '角色备注',
  `calls_total`     bigint        DEFAULT 0 COMMENT '累计调用（展示）',
  `cost_total`      decimal(18,2) DEFAULT 0 COMMENT '累计成本（展示）',
  `delete_flag`     varchar(32)   DEFAULT 'NOT_DELETE' COMMENT '删除标志',
  `create_time`     datetime      DEFAULT NULL,
  `create_user`     varchar(20)   DEFAULT NULL,
  `update_time`     datetime      DEFAULT NULL,
  `update_user`     varchar(20)   DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_ai_model_vendor` (`vendor`) USING BTREE,
  KEY `idx_ai_model_kind` (`kind`) USING BTREE,
  KEY `idx_ai_model_enabled` (`enabled`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI 模型登记';

CREATE TABLE IF NOT EXISTS `gov_ai_route` (
  `id`                 varchar(20)  NOT NULL COMMENT '主键',
  `revision`           int          NOT NULL DEFAULT 1,
  `status`             varchar(32)  DEFAULT 'ok' COMMENT 'ok/off',
  `ws`                 varchar(64)  DEFAULT 'default' COMMENT '策略归属空间',
  `remark`             varchar(512) DEFAULT NULL,
  `scene`              varchar(32)  NOT NULL COMMENT 'sql/script/diagnose/manual/sandbox/embed',
  `ws_scope`           varchar(64)  NOT NULL DEFAULT '*' COMMENT '* 或具体 ws',
  `primary_model_id`   varchar(20)  NOT NULL COMMENT '主模型',
  `fallback_model_id`  varchar(20)  DEFAULT NULL COMMENT '备模型',
  `enabled`            tinyint      NOT NULL DEFAULT 1,
  `delete_flag`        varchar(32)  DEFAULT 'NOT_DELETE',
  `create_time`        datetime     DEFAULT NULL,
  `create_user`        varchar(20)  DEFAULT NULL,
  `update_time`        datetime     DEFAULT NULL,
  `update_user`        varchar(20)  DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_ai_route_scene_ws` (`scene`, `ws_scope`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI 场景路由';

CREATE TABLE IF NOT EXISTS `gov_ai_usage_daily` (
  `id`                 varchar(20)   NOT NULL COMMENT '主键',
  `day`                date          NOT NULL COMMENT '日',
  `ws`                 varchar(64)   NOT NULL DEFAULT 'default',
  `model_id`           varchar(20)   NOT NULL,
  `calls`              bigint        NOT NULL DEFAULT 0,
  `prompt_tokens`      bigint        NOT NULL DEFAULT 0,
  `completion_tokens`  bigint        NOT NULL DEFAULT 0,
  `cost_amount`        decimal(18,4) NOT NULL DEFAULT 0,
  `currency`           varchar(8)    DEFAULT 'CNY',
  `create_time`        datetime      DEFAULT NULL,
  `update_time`        datetime      DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_ai_usage_day` (`day`, `ws`, `model_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI 日用量聚合';

-- ========== 助手会话 ==========
CREATE TABLE IF NOT EXISTS `gov_ai_session` (
  `id`              varchar(20)  NOT NULL COMMENT '主键',
  `revision`        int          NOT NULL DEFAULT 1,
  `status`          varchar(32)  DEFAULT 'active' COMMENT 'active/archived',
  `ws`              varchar(64)  DEFAULT 'default',
  `remark`          varchar(512) DEFAULT NULL,
  `user_id`         varchar(20)  DEFAULT NULL COMMENT '用户',
  `title`           varchar(256) DEFAULT NULL COMMENT '标题',
  `model_override`  varchar(20)  DEFAULT NULL COMMENT '会话临时模型',
  `delete_flag`     varchar(32)  DEFAULT 'NOT_DELETE',
  `create_time`     datetime     DEFAULT NULL,
  `create_user`     varchar(20)  DEFAULT NULL,
  `update_time`     datetime     DEFAULT NULL,
  `update_user`     varchar(20)  DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_ai_session_ws_user` (`ws`, `user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI 对话会话';

CREATE TABLE IF NOT EXISTS `gov_ai_turn` (
  `id`                  varchar(20)   NOT NULL COMMENT '主键',
  `session_id`          varchar(20)   NOT NULL,
  `role`                varchar(16)   NOT NULL COMMENT 'user/assistant/system',
  `intent`              varchar(32)   DEFAULT NULL COMMENT 'nl2sql/...',
  `content`             mediumtext    COMMENT '正文',
  `citations_json`      mediumtext    COMMENT '引用 JSON',
  `prompt_tokens`       int           DEFAULT 0,
  `completion_tokens`   int           DEFAULT 0,
  `model_id`            varchar(20)   DEFAULT NULL,
  `latency_ms`          int           DEFAULT NULL,
  `create_time`         datetime      DEFAULT NULL,
  `create_user`         varchar(20)   DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_ai_turn_session` (`session_id`, `create_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI 对话轮次';

CREATE TABLE IF NOT EXISTS `gov_ai_action_log` (
  `id`          varchar(20)   NOT NULL COMMENT '主键',
  `session_id`  varchar(20)   DEFAULT NULL,
  `turn_id`     varchar(20)   DEFAULT NULL,
  `action`      varchar(64)   NOT NULL COMMENT 'run-sql/deep-link/...',
  `detail_json` mediumtext    COMMENT '详情',
  `create_time` datetime      DEFAULT NULL,
  `create_user` varchar(20)   DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_ai_action_session` (`session_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI 动作审计';

-- ========== 知识库 ==========
CREATE TABLE IF NOT EXISTS `gov_kb_entry` (
  `id`              varchar(20)   NOT NULL COMMENT '主键',
  `revision`        int           NOT NULL DEFAULT 1,
  `status`          varchar(32)   DEFAULT 'ready' COMMENT 'indexing/ready/failed',
  `ws`              varchar(64)   DEFAULT 'default',
  `remark`          varchar(512)  DEFAULT NULL,
  `cat`             varchar(32)   NOT NULL COMMENT 'term/dict/practice/faq/manual',
  `title`           varchar(256)  NOT NULL,
  `body`            mediumtext    COMMENT '正文',
  `source`          varchar(16)   DEFAULT 'manual' COMMENT 'manual/upload',
  `file_name`       varchar(256)  DEFAULT NULL,
  `strategy`        varchar(32)   DEFAULT 'fixed' COMMENT 'fixed/paragraph/heading',
  `chunk_size`      int           DEFAULT 500,
  `overlap`         int           DEFAULT 50,
  `separator`       varchar(32)   DEFAULT '\n\n',
  `embed_model_id`  varchar(20)   DEFAULT NULL,
  `refs_json`       text          COMMENT '关联资产/指标 JSON',
  `cite_cnt`        bigint        DEFAULT 0 COMMENT '累计引用',
  `index_error`     varchar(512)  DEFAULT NULL,
  `delete_flag`     varchar(32)   DEFAULT 'NOT_DELETE',
  `create_time`     datetime      DEFAULT NULL,
  `create_user`     varchar(20)   DEFAULT NULL,
  `update_time`     datetime      DEFAULT NULL,
  `update_user`     varchar(20)   DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_kb_entry_cat` (`cat`) USING BTREE,
  KEY `idx_kb_entry_ws` (`ws`) USING BTREE,
  KEY `idx_kb_entry_status` (`status`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='知识条目';

CREATE TABLE IF NOT EXISTS `gov_kb_chunk` (
  `id`           varchar(20)  NOT NULL COMMENT '主键',
  `entry_id`     varchar(20)  NOT NULL,
  `ordinal`      int          NOT NULL DEFAULT 0,
  `text_content` mediumtext   NOT NULL,
  `token_est`    int          DEFAULT 0,
  `create_time`  datetime     DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_kb_chunk_entry` (`entry_id`, `ordinal`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='知识分片（P0 关键词检索；向量在旁路 pgvector）';

CREATE TABLE IF NOT EXISTS `gov_kb_cite_stat` (
  `id`          varchar(20) NOT NULL COMMENT '主键',
  `entry_id`    varchar(20) NOT NULL,
  `day`         date        NOT NULL,
  `cite_cnt`    bigint      NOT NULL DEFAULT 0,
  `create_time` datetime    DEFAULT NULL,
  `update_time` datetime    DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uq_kb_cite_day` (`entry_id`, `day`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='知识引用日统计';

-- ========== 种子：模型（对齐前端 AI_MODELS） ==========
INSERT INTO `gov_ai_model`
(`id`,`revision`,`status`,`ws`,`name`,`vendor`,`kind`,`model_name`,`base_url`,`vault_path`,`key_mask`,`context_tokens`,`price_unit`,`input_rate`,`output_rate`,`enabled`,`latency_ms`,`role_label`,`calls_total`,`cost_total`,`delete_flag`,`create_time`)
VALUES
('aim_gpt4o',1,'ok','default','GPT-4o','OpenAI','chat','gpt-4o','https://api.openai.com/v1','secret/lakehouse/ai/aim_gpt4o','sk-****...a3f9','128K','usd_1m',2.5,10,1,1200,'默认·强推理',86000,1240.00,'NOT_DELETE',NOW()),
('aim_claude',1,'ok','default','Claude 3.5 Sonnet','Anthropic','chat','claude-3-5-sonnet','https://api.anthropic.com/v1','secret/lakehouse/ai/aim_claude','sk-ant-****...7c2e','200K','usd_1m',3,15,1,1500,'代码擅长',24000,480.00,'NOT_DELETE',NOW()),
('aim_qwen',1,'ok','default','通义千问 Max','阿里云','chat','qwen-max','https://dashscope.aliyuncs.com/compatible-mode/v1','secret/lakehouse/ai/aim_qwen','sk-****...9b1d','128K','cny_1k',0.04,0.12,1,800,'本地化',12000,86.00,'NOT_DELETE',NOW()),
('aim_ds',1,'warn','default','DeepSeek V3','DeepSeek','chat','deepseek-chat','https://api.deepseek.com/v1','secret/lakehouse/ai/aim_ds','sk-****...4f8a','128K','cny_1m',1,2,1,1100,'性价比·Key 将过期',6000,36.00,'NOT_DELETE',NOW()),
('aim_local',1,'off','default','Qwen2.5-14B 本地','自建','chat','qwen2.5-14b','http://10.4.0.8:8080/v1',NULL,'无需（内网）','32K','free',0,0,0,NULL,'备用·内网部署',0,0.00,'NOT_DELETE',NOW()),
('aim_embed',1,'ok','default','text-embedding-3-small','OpenAI','embed','text-embedding-3-small','https://api.openai.com/v1','secret/lakehouse/ai/aim_embed','sk-****...emb1','8K','usd_1m',0.02,0,1,NULL,'默认 Embedding',0,0.00,'NOT_DELETE',NOW());

INSERT INTO `gov_ai_route`
(`id`,`revision`,`status`,`ws`,`scene`,`ws_scope`,`primary_model_id`,`fallback_model_id`,`enabled`,`delete_flag`,`create_time`)
VALUES
('air_sql',1,'ok','default','sql','*','aim_gpt4o','aim_ds',1,'NOT_DELETE',NOW()),
('air_script',1,'ok','default','script','*','aim_claude','aim_gpt4o',1,'NOT_DELETE',NOW()),
('air_diag',1,'ok','default','diagnose','ws_trade','aim_gpt4o','aim_qwen',1,'NOT_DELETE',NOW()),
('air_manual',1,'ok','default','manual','*','aim_qwen','aim_ds',1,'NOT_DELETE',NOW()),
('air_sandbox',1,'off','default','sandbox','ws_sandbox','aim_local','aim_ds',0,'NOT_DELETE',NOW()),
('air_embed',1,'ok','default','embed','*','aim_embed',NULL,1,'NOT_DELETE',NOW());

-- ========== 种子：知识（对齐前端 KB_ITEMS 摘要） ==========
INSERT INTO `gov_kb_entry`
(`id`,`revision`,`status`,`ws`,`cat`,`title`,`body`,`source`,`strategy`,`chunk_size`,`overlap`,`embed_model_id`,`refs_json`,`cite_cnt`,`delete_flag`,`create_time`)
VALUES
('kb_gmv',1,'ready','default','term','GMV（Gross Merchandise Volume）','成交总额。口径：已支付订单的 pay_amt 之和，含运费，不含退款。单位元，保留 2 位小数。关联指标 M-0001。','manual','paragraph',500,50,'aim_embed','{\"metricCode\":\"M-0001\",\"link\":\"/metrics\"}',120,'NOT_DELETE',NOW()),
('kb_status',1,'ready','default','term','订单状态码值 STD-C0021','DRAFT=0, PAID=1, SHIPPED=2, DELIVERED=3, REFUNDED=4, CLOSED=5。禁止源库自行扩展。','manual','paragraph',500,50,'aim_embed','{\"asset\":\"dwd_order_detail.order_status\",\"link\":\"/catalog\"}',40,'NOT_DELETE',NOW()),
('kb_mask',1,'ready','default','term','买家手机号脱敏策略','PII 字段。默认脱敏：保留前 3 后 4（138****1234）。明文需安全岗二次审批。','manual','paragraph',500,50,'aim_embed','{\"asset\":\"dwd_user_info.buyer_mobile\",\"link\":\"/security\"}',55,'NOT_DELETE',NOW()),
('kb_dict',1,'ready','default','dict','dwd_order_detail 字段字典','23 列完整说明：order_id(PK) / order_no / buyer_mobile(脱敏) / pay_amt(元,2位) / order_channel(码值) …','manual','heading',500,50,'aim_embed','{\"asset\":\"dwd_order_detail\",\"link\":\"/catalog\"}',80,'NOT_DELETE',NOW()),
('kb_faq_plain',1,'ready','default','faq','怎么申请敏感列明文权限？','申请审批 → 勾选「申请明文」→ 填详细用途 → 资产 Owner 审 → 安全岗二次加签 → 写入 Gravitino → 到期自动回收。','manual','paragraph',500,50,'aim_embed','{\"link\":\"/apply\"}',94,'NOT_DELETE',NOW()),
('kb_faq_recon',1,'ready','default','faq','对账失败后看板为什么不更新？','ads_gmv_board 湖/CK 分区级对账失败 → 自动摘牌黄金数据集 → Superset 看板冻结 → 需修复后重跑对账通过才恢复。','manual','paragraph',500,50,'aim_embed','{\"link\":\"/rootcause\"}',70,'NOT_DELETE',NOW()),
('kb_practice_ice',1,'ready','default','practice','Iceberg 表分区最佳实践','按 dt 日分区 + order_id 哈希分桶。小表（<1亿）可不分桶。历史分区 90 天后转冷归档。','manual','paragraph',500,50,'aim_embed',NULL,30,'NOT_DELETE',NOW()),
('kb_manual_apply',1,'ready','default','manual','平台使用手册 v1.1 · 第 3 章 数据申请','步骤：选资产 → 选列 → 选时效 → 填用途 → 审批链（Owner/安全岗）→ Gravitino 写入 → 即席查询可见。含驳回率 12.5% 常见原因。','manual','heading',500,50,'aim_embed',NULL,20,'NOT_DELETE',NOW());

INSERT INTO `gov_kb_chunk` (`id`,`entry_id`,`ordinal`,`text_content`,`token_est`,`create_time`)
SELECT CONCAT('kbc_', SUBSTRING(id,4), '_0'), id, 0, CONCAT(title, '\n', body), CHAR_LENGTH(CONCAT(title, body)), NOW()
FROM `gov_kb_entry` WHERE id LIKE 'kb_%';
