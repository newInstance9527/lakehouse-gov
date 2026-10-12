-- 数据服务：ES Query DSL 等门户原生引擎正文（SQL/Groovy 仍主要在 SQLREST）
ALTER TABLE `dataapi_api_binding`
  ADD COLUMN `engine` varchar(32) NOT NULL DEFAULT 'SQL' COMMENT 'SQL/GROOVY/ES_DSL' AFTER `source_kind`,
  ADD COLUMN `script_text` mediumtext DEFAULT NULL COMMENT '门户原生脚本（如 ES Query DSL JSON）' AFTER `engine`;
