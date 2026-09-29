-- 数据域 SoT（gov_domain）；商品规范码 goods；product 作读路径别名
-- 种子：trade/user/goods/marketing/finance/common；无演示 KPI

CREATE TABLE IF NOT EXISTS `gov_domain` (
  `id`            varchar(20)   NOT NULL COMMENT '主键',
  `ws`            varchar(64)   NOT NULL DEFAULT 'default' COMMENT '工作空间（全局域可用 default）',
  `domain_code`   varchar(64)   NOT NULL COMMENT '域编码小写',
  `name`          varchar(128)  NOT NULL COMMENT '展示名',
  `owner`         varchar(64)   DEFAULT NULL COMMENT '责任人',
  `sort_no`       int           NOT NULL DEFAULT 0 COMMENT '排序',
  `status`        varchar(16)   NOT NULL DEFAULT 'active' COMMENT 'active|disabled',
  `remark`        varchar(512)  DEFAULT NULL COMMENT '备注',
  `extra_json`    mediumtext    COMMENT '扩展（aliases 等）',
  `delete_flag`   varchar(32)   DEFAULT 'NOT_DELETE',
  `create_time`   datetime      DEFAULT NULL,
  `create_user`   varchar(20)   DEFAULT NULL,
  `update_time`   datetime      DEFAULT NULL,
  `update_user`   varchar(20)   DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_gov_domain_code` (`domain_code`) USING BTREE,
  KEY `idx_gov_domain_status` (`status`,`sort_no`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='业务数据域 SoT';

INSERT INTO `gov_domain` (`id`,`ws`,`domain_code`,`name`,`owner`,`sort_no`,`status`,`remark`,`extra_json`,`delete_flag`,`create_time`)
VALUES
('7500000000000000001','default','trade','交易域',NULL,10,'active','核心交易','{"aliases":["交易","交易域"]}', 'NOT_DELETE', NOW()),
('7500000000000000002','default','user','用户域',NULL,20,'active','用户/会员','{"aliases":["用户","用户域","流量"]}', 'NOT_DELETE', NOW()),
('7500000000000000003','default','goods','商品域',NULL,30,'active','商品/SKU','{"aliases":["商品","商品域","product"]}', 'NOT_DELETE', NOW()),
('7500000000000000004','default','marketing','营销域',NULL,40,'active',NULL,'{"aliases":["营销","营销域"]}', 'NOT_DELETE', NOW()),
('7500000000000000005','default','finance','财务域',NULL,50,'active',NULL,'{"aliases":["财务","财务域"]}', 'NOT_DELETE', NOW()),
('7500000000000000006','default','common','通用',NULL,5,'active','跨域/未归类','{"aliases":["通用"]}', 'NOT_DELETE', NOW())
ON DUPLICATE KEY UPDATE `name`=VALUES(`name`), `status`=VALUES(`status`), `extra_json`=VALUES(`extra_json`);

-- 资产历史 product → goods
UPDATE `gov_asset` SET `domain_code` = 'goods'
WHERE `domain_code` IN ('product', '商品', '商品域') AND (`delete_flag` IS NULL OR `delete_flag` = 'NOT_DELETE');

-- 指标历史中文 / product → 规范码
UPDATE `gov_metric` SET `domain_code` = 'trade'
WHERE `domain_code` IN ('交易', '交易域') AND (`delete_flag` IS NULL OR `delete_flag` = 'NOT_DELETE');
UPDATE `gov_metric` SET `domain_code` = 'user'
WHERE `domain_code` IN ('用户', '用户域', '流量') AND (`delete_flag` IS NULL OR `delete_flag` = 'NOT_DELETE');
UPDATE `gov_metric` SET `domain_code` = 'goods'
WHERE `domain_code` IN ('商品', '商品域', 'product') AND (`delete_flag` IS NULL OR `delete_flag` = 'NOT_DELETE');

-- 标准字段：中文域 → 规范码（能匹配的）
UPDATE `gov_std_field` SET `domain_code` = 'trade'
WHERE `domain_code` IN ('交易', '交易域') AND (`delete_flag` IS NULL OR `delete_flag` = 'NOT_DELETE');
UPDATE `gov_std_field` SET `domain_code` = 'user'
WHERE `domain_code` IN ('用户', '用户域') AND (`delete_flag` IS NULL OR `delete_flag` = 'NOT_DELETE');
UPDATE `gov_std_field` SET `domain_code` = 'goods'
WHERE `domain_code` IN ('商品', '商品域', 'product') AND (`delete_flag` IS NULL OR `delete_flag` = 'NOT_DELETE');
UPDATE `gov_std_field` SET `domain_code` = 'marketing'
WHERE `domain_code` IN ('营销', '营销域') AND (`delete_flag` IS NULL OR `delete_flag` = 'NOT_DELETE');
UPDATE `gov_std_field` SET `domain_code` = 'finance'
WHERE `domain_code` IN ('财务', '财务域') AND (`delete_flag` IS NULL OR `delete_flag` = 'NOT_DELETE');
UPDATE `gov_std_field` SET `domain_code` = 'common'
WHERE `domain_code` IN ('通用') AND (`delete_flag` IS NULL OR `delete_flag` = 'NOT_DELETE');
