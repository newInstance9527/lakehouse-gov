-- dataapi_api_binding：关联门户数据源与 SQLREST 数据源投影

SET @db := DATABASE();

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@db AND TABLE_NAME='dataapi_api_binding' AND COLUMN_NAME='portal_ds_id');
SET @sql := IF(@c=0,
  'ALTER TABLE `dataapi_api_binding` ADD COLUMN `portal_ds_id` varchar(20) DEFAULT NULL COMMENT ''门户 ig_datasource.id'' AFTER `source_ref`',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@db AND TABLE_NAME='dataapi_api_binding' AND COLUMN_NAME='sqlrest_datasource_id');
SET @sql := IF(@c=0,
  'ALTER TABLE `dataapi_api_binding` ADD COLUMN `sqlrest_datasource_id` varchar(64) DEFAULT NULL COMMENT ''SQLREST datasource id'' AFTER `portal_ds_id`',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @c := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=@db AND TABLE_NAME='dataapi_api_binding' AND INDEX_NAME='idx_dataapi_portal_ds');
SET @sql := IF(@c=0,
  'ALTER TABLE `dataapi_api_binding` ADD KEY `idx_dataapi_portal_ds` (`portal_ds_id`)',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
