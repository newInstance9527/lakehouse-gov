-- 知识库 scope：workspace（空间私有）| platform（部署内公用）
-- 对齐 doc/知识库.md §7：手册可标 scope=platform 全局可读；写权限受限

ALTER TABLE `gov_kb_entry`
  ADD COLUMN `scope` varchar(16) NOT NULL DEFAULT 'workspace'
    COMMENT 'workspace|platform' AFTER `ws`;

ALTER TABLE `gov_kb_entry`
  ADD KEY `idx_kb_entry_scope` (`scope`) USING BTREE;

-- 既有「平台使用手册」升为公用知识（ws 哨兵 _platform，便于 Milvus 过滤）
UPDATE `gov_kb_entry`
SET `scope` = 'platform',
    `ws` = '_platform'
WHERE `id` = 'kb_manual_apply';
