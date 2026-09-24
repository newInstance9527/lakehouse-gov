-- cp_release：MR/PR 与申请中心脚本发布单号
ALTER TABLE `cp_release`
  ADD COLUMN `apply_ticket_no` varchar(64) DEFAULT NULL COMMENT 'script_publish 申请单号 SCR-xxx' AFTER `rolled_to_tag`,
  ADD COLUMN `pr_number` int DEFAULT NULL COMMENT 'Gitea PR 号' AFTER `apply_ticket_no`,
  ADD COLUMN `pr_url` varchar(512) DEFAULT NULL COMMENT 'Gitea PR URL' AFTER `pr_number`,
  ADD COLUMN `pr_state` varchar(32) DEFAULT NULL COMMENT 'open/merged/closed' AFTER `pr_url`,
  ADD COLUMN `review_branch` varchar(128) DEFAULT NULL COMMENT '评审分支 review/{id}' AFTER `pr_state`;
