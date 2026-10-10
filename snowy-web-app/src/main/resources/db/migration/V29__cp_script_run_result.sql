-- 试跑结果抽样：结果表与调度日志尾，供开发页回看。正文 SQL 仍在 Git。

ALTER TABLE `cp_script_run`
  ADD COLUMN `result_json` mediumtext COMMENT '试跑抽样：columns/rows/log/source' AFTER `dur_ms`;
