-- D5：外发模型安全岗标记（egress_kind + egress_approved）
-- 规格：doc/AI模型管理.md §7 · AI平台能力-跨模块待办 §2.10

ALTER TABLE `gov_ai_model`
  ADD COLUMN `egress_kind`     varchar(16)  NOT NULL DEFAULT 'egress' COMMENT 'local=内网/自建；egress=外发云' AFTER `enabled`,
  ADD COLUMN `egress_approved` tinyint      NOT NULL DEFAULT 0 COMMENT '安全岗已评估外发可用' AFTER `egress_kind`;

-- 内网/自建/免费 → local
UPDATE `gov_ai_model`
SET `egress_kind` = 'local',
    `egress_approved` = 1
WHERE `vendor` = '自建'
   OR `price_unit` = 'free'
   OR `base_url` LIKE 'http://10.%'
   OR `base_url` LIKE 'http://192.168.%'
   OR `base_url` LIKE 'http://172.%'
   OR `base_url` LIKE 'http://127.%'
   OR `base_url` LIKE 'http://localhost%'
   OR `base_url` LIKE 'https://localhost%';

-- 存量外发模型祖父条款：已登记种子保持可用，新接入默认需安全岗标记
UPDATE `gov_ai_model`
SET `egress_kind` = 'egress',
    `egress_approved` = 1
WHERE `egress_kind` <> 'local';
