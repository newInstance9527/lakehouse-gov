-- dataapi 发布申请单号（对接 apply_ticket.ticket_type=api_publish）
ALTER TABLE `dataapi_api_binding`
  ADD COLUMN `publish_ticket_no` varchar(64) DEFAULT NULL COMMENT '发布审批单号 API-xxx' AFTER `last_error`;
