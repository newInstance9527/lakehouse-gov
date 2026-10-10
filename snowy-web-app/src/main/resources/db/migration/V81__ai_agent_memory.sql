-- Agent 记忆：会话工作摘要 + 知识库纪要质量/老化字段
-- L1: gov_ai_session.memory_summary
-- L2/L3: gov_kb_entry 纪要质量分、访问时间、生命周期

ALTER TABLE `gov_ai_session`
    ADD COLUMN `memory_summary` TEXT NULL COMMENT '会话工作记忆滚动摘要' AFTER `model_override`;

ALTER TABLE `gov_kb_entry`
    ADD COLUMN `quality_score` DECIMAL(4, 1) NULL COMMENT '质量分 0-10，人工优先' AFTER `cite_cnt`,
    ADD COLUMN `score_source` VARCHAR(16) NULL COMMENT 'agent|human' AFTER `quality_score`,
    ADD COLUMN `score_reason` VARCHAR(512) NULL COMMENT '打分理由' AFTER `score_source`,
    ADD COLUMN `last_access_at` DATETIME NULL COMMENT '最后检索/引用时间' AFTER `score_reason`,
    ADD COLUMN `lifecycle_status` VARCHAR(16) NOT NULL DEFAULT 'active' COMMENT 'active|archive|deleted' AFTER `last_access_at`,
    ADD COLUMN `tags_json` VARCHAR(1024) NULL COMMENT '标签 JSON 数组' AFTER `lifecycle_status`;

CREATE INDEX `idx_gov_kb_entry_lifecycle` ON `gov_kb_entry` (`lifecycle_status`, `last_access_at`);
CREATE INDEX `idx_gov_kb_entry_cat_score` ON `gov_kb_entry` (`cat`, `quality_score`);
