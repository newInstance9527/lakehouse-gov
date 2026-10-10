-- 存储画像回写 + 合规定向过期参数
-- 规格：doc/存储趋势.md job.storage.profile_daily（先回写本表，VM 后接）
--       doc/生命周期.md 作业效果回写；doc/合规删除.md retain_last / req_no

ALTER TABLE `gov_lc_run`
  ADD COLUMN `req_no` varchar(64) DEFAULT NULL COMMENT '合规删除请求号；空=普通治理' AFTER `remark`,
  ADD COLUMN `retain_last` int DEFAULT NULL COMMENT 'expire 覆盖保留快照数；仅合规工单允许 1' AFTER `req_no`;

ALTER TABLE `gov_lc_table_stat`
  ADD COLUMN `active_bytes` bigint DEFAULT NULL COMMENT '当前快照活跃字节（$files）' AFTER `size_bytes`,
  ADD COLUMN `reclaimable_bytes` bigint DEFAULT NULL COMMENT '旧快照可回收字节（$all_files - $files）' AFTER `active_bytes`,
  ADD COLUMN `small_file_count` bigint DEFAULT NULL COMMENT '小于 32MB 的数据文件数' AFTER `avg_file_bytes`,
  ADD COLUMN `small_file_ratio` decimal(8,4) DEFAULT NULL COMMENT '小文件占比，0-1' AFTER `small_file_count`,
  ADD COLUMN `collect_status` varchar(16) DEFAULT NULL COMMENT 'ok/partial/failed；空表示尚未画像、仍为种子' AFTER `policy_label`,
  ADD COLUMN `collect_error` varchar(512) DEFAULT NULL COMMENT '本表采集失败或降级原因' AFTER `collect_status`;
