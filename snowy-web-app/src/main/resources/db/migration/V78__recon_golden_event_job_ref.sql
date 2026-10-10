-- rewrite_ck / 黄金事件：落 DS 作业引用
ALTER TABLE `recon_golden_event`
  ADD COLUMN `job_ref` varchar(128) NULL COMMENT 'DS workflow 或 processInstance 引用' AFTER `trace_id`,
  ADD COLUMN `ds_instance_id` varchar(64) NULL COMMENT 'DS processInstanceId' AFTER `job_ref`;
