-- API 目录自定义标签：绑定投影 tags_json（JSON 字符串数组，如 ["核心","报表"]）

ALTER TABLE `dataapi_api_binding`
  ADD COLUMN `tags_json` varchar(1024) DEFAULT NULL COMMENT '自定义标签 JSON 字符串数组' AFTER `publish_ticket_no`;
