-- 空间优先 + 企业共享层（可见平面）；授权仍走申请中心 → Grav
-- 规格：doc/工作空间.md（2026-09-24 重定型）

-- 空间类型：team / enterprise / isolated
ALTER TABLE `gov_ws`
  ADD COLUMN `ws_kind` varchar(32) NOT NULL DEFAULT 'team' COMMENT 'team/enterprise/isolated' AFTER `ws_code`,
  ADD COLUMN `tech_ns` varchar(128) DEFAULT NULL COMMENT '隔离空间可选技术命名空间/schema前缀' AFTER `preferred_schemas`;

-- 资产可见性（门户列表）；ws 列语义 = home_ws（认责与成本）
ALTER TABLE `gov_asset`
  ADD COLUMN `visibility` varchar(32) NOT NULL DEFAULT 'private_ws' COMMENT 'private_ws/shared_enterprise/listed_public' AFTER `ws`,
  ADD COLUMN `share_status` varchar(32) NOT NULL DEFAULT 'none' COMMENT 'none/pending/published' AFTER `visibility`;

CREATE INDEX `idx_gov_asset_visibility` ON `gov_asset` (`visibility`, `share_status`);
CREATE INDEX `idx_gov_ws_kind` ON `gov_ws` (`ws_kind`);

-- 企业共享空间（浏览已发布资产的入口容器；不自动授权）
INSERT INTO `gov_ws` (
  `id`, `revision`, `status`, `ws`, `remark`, `ws_code`, `ws_kind`, `name`, `icon`,
  `domain_code`, `cost_center`, `detail`, `delete_flag`, `create_time`, `update_time`
) SELECT
  'ws_enterprise_001', 1, 'active', 'enterprise', '企业共享层容器：仅承载已发布共享资产的浏览上下文',
  'enterprise', 'enterprise', '企业共享', '🏢',
  NULL, 'CC-ENTERPRISE', '跨团队发现入口；看见≠能查，SELECT 仍走申请中心',
  'NOT_DELETE', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `gov_ws` WHERE `ws_code` = 'enterprise');

INSERT INTO `gov_ws_quota` (
  `id`, `revision`, `status`, `ws`, `ws_code`,
  `storage_quota_tb`, `storage_used_tb`, `cu_quota`, `cu_used`,
  `trino_quota`, `trino_used`, `api_qps_quota`, `api_qps_used`,
  `delete_flag`, `create_time`, `update_time`
) SELECT
  'wsq_enterprise_001', 1, 'ok', 'enterprise', 'enterprise',
  0, 0, 0, 0, 0, 0, 0, 0,
  'NOT_DELETE', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `gov_ws_quota` WHERE `ws_code` = 'enterprise');
