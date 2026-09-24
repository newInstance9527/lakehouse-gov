-- 第三方投影外部名绑定：ext_id 全局唯一（kind:ws__code）
-- SQLREST / APISIX / Marquez 投影统一走 ExternalName.of(ws, code)

ALTER TABLE `ig_consumer_binding`
  ADD COLUMN `ext_id` varchar(192) DEFAULT NULL COMMENT '外部名绑定键 kind:ws__code；全局唯一' AFTER `consumer_id`;

-- 回填：sqlrest 绑定从 projection.sqlrestName 或 ds 推导
UPDATE `ig_consumer_binding` b
  LEFT JOIN `ig_datasource` d ON d.id = b.ds_id
SET b.ext_id = CONCAT(
  CASE
    WHEN LOWER(IFNULL(b.consumer_type, '')) = 'sqlrest' THEN 'sqlrest_ds'
    ELSE LOWER(IFNULL(NULLIF(TRIM(b.consumer_type), ''), 'ext'))
  END,
  ':',
  LOWER(REPLACE(REPLACE(IFNULL(NULLIF(TRIM(d.ws), ''), 'default'), ' ', '_'), '/', '_')),
  '__',
  LOWER(REPLACE(REPLACE(IFNULL(NULLIF(TRIM(d.ds_code), ''), IFNULL(d.id, b.ds_id)), ' ', '_'), '/', '_'))
)
WHERE b.ext_id IS NULL
  AND (b.delete_flag IS NULL OR b.delete_flag = 'NOT_DELETE');

-- 冲突行：保留最小 id，其余清空以便加唯一索引（需人工处理或下次投影重写）
UPDATE `ig_consumer_binding` b
  INNER JOIN (
    SELECT ext_id, MIN(id) AS keep_id
    FROM `ig_consumer_binding`
    WHERE ext_id IS NOT NULL AND ext_id <> ''
    GROUP BY ext_id
    HAVING COUNT(*) > 1
  ) d ON b.ext_id = d.ext_id AND b.id <> d.keep_id
SET b.ext_id = NULL;

ALTER TABLE `ig_consumer_binding`
  ADD UNIQUE KEY `uq_ig_cb_ext_id` (`ext_id`) USING BTREE;

ALTER TABLE `dataapi_api_binding`
  ADD COLUMN `ext_id` varchar(192) DEFAULT NULL COMMENT 'SQLREST 投影外部名键 sqlrest_api:ws__code；全局唯一' AFTER `apisix_route_id`;

UPDATE `dataapi_api_binding`
SET `ext_id` = CONCAT(
  'sqlrest_api:',
  LOWER(REPLACE(REPLACE(IFNULL(NULLIF(TRIM(`ws`), ''), 'default'), ' ', '_'), '/', '_')),
  '__',
  LOWER(REPLACE(REPLACE(IFNULL(NULLIF(TRIM(`id`), ''), 'api'), ' ', '_'), '/', '_'))
)
WHERE `ext_id` IS NULL
  AND (`delete_flag` IS NULL OR `delete_flag` = 'NOT_DELETE');

UPDATE `dataapi_api_binding` b
  INNER JOIN (
    SELECT ext_id, MIN(id) AS keep_id
    FROM `dataapi_api_binding`
    WHERE ext_id IS NOT NULL AND ext_id <> ''
    GROUP BY ext_id
    HAVING COUNT(*) > 1
  ) d ON b.ext_id = d.ext_id AND b.id <> d.keep_id
SET b.ext_id = NULL;

ALTER TABLE `dataapi_api_binding`
  ADD UNIQUE KEY `uq_dataapi_ext_id` (`ext_id`) USING BTREE;

-- APISIX route id 全局唯一（允许多 NULL）
UPDATE `dataapi_api_binding` b
  INNER JOIN (
    SELECT apisix_route_id, MIN(id) AS keep_id
    FROM `dataapi_api_binding`
    WHERE apisix_route_id IS NOT NULL AND apisix_route_id <> ''
      AND (delete_flag IS NULL OR delete_flag = 'NOT_DELETE')
    GROUP BY apisix_route_id
    HAVING COUNT(*) > 1
  ) d ON b.apisix_route_id = d.apisix_route_id AND b.id <> d.keep_id
SET b.apisix_route_id = NULL;

ALTER TABLE `dataapi_api_binding`
  ADD UNIQUE KEY `uq_dataapi_apisix_route` (`apisix_route_id`) USING BTREE;
