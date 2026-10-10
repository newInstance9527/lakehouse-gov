-- 操作权限：sec_auth_grant 支持多资源类型（asset/datasource/etl + 预留）

SET @db := DATABASE();

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@db AND TABLE_NAME='sec_auth_grant' AND COLUMN_NAME='resource_type');
SET @sql := IF(@c=0,
  'ALTER TABLE `sec_auth_grant` ADD COLUMN `resource_type` varchar(32) DEFAULT NULL COMMENT ''asset/datasource/etl/...'' AFTER `subject_id`',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@db AND TABLE_NAME='sec_auth_grant' AND COLUMN_NAME='resource_id');
SET @sql := IF(@c=0,
  'ALTER TABLE `sec_auth_grant` ADD COLUMN `resource_id` varchar(64) DEFAULT NULL COMMENT ''资源主键'' AFTER `resource_type`',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 回填历史资产授权
UPDATE `sec_auth_grant`
SET `resource_type` = 'asset',
    `resource_id` = `asset_id`
WHERE (`resource_type` IS NULL OR `resource_type` = '')
  AND `asset_id` IS NOT NULL
  AND `asset_id` <> '';

SET @i := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=@db AND TABLE_NAME='sec_auth_grant' AND INDEX_NAME='idx_sec_grant_resource');
SET @sql := IF(@i=0,
  'ALTER TABLE `sec_auth_grant` ADD KEY `idx_sec_grant_resource` (`resource_type`,`resource_id`,`status`)',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
