-- 数据标准：命名规范预置（业界湖仓 / 分层数仓通用约定，ws=default）
-- 唯一键 (ws, layer, pattern)；INSERT IGNORE 可重复执行

INSERT IGNORE INTO `gov_std_naming` (
  `id`, `revision`, `status`, `ws`, `remark`,
  `pattern`, `example`, `layer`,
  `delete_flag`, `create_time`, `update_time`
) VALUES
-- 分层表
('std_nm_ods_01', 1, 'ok', 'default', '平台预置·分层数仓',
 'ods_<源系统>_<表名>', 'ods_trade.s_order', 'ODS',
 'NOT_DELETE', NOW(), NOW()),
('std_nm_ods_02', 1, 'ok', 'default', '平台预置·贴源可带库名',
 'ods_<源系统>.<原表名>', 'ods_trade.s_order_detail', 'ODS',
 'NOT_DELETE', NOW(), NOW()),
('std_nm_dwd_01', 1, 'ok', 'default', '平台预置·明细层',
 'dwd_<域>_<实体>_<粒度>', 'dwd_trade.dwd_order_detail_d', 'DWD',
 'NOT_DELETE', NOW(), NOW()),
('std_nm_dwd_02', 1, 'ok', 'default', '平台预置·明细无粒度后缀时默认日',
 'dwd_<域>_<实体>', 'dwd_trade.dwd_order_detail', 'DWD',
 'NOT_DELETE', NOW(), NOW()),
('std_nm_dws_01', 1, 'ok', 'default', '平台预置·汇总层',
 'dws_<域>_<汇总主题>_<周期>', 'dws_trade.dws_order_gmv_1d', 'DWS',
 'NOT_DELETE', NOW(), NOW()),
('std_nm_ads_01', 1, 'ok', 'default', '平台预置·应用层',
 'ads_<业务场景>', 'ads.ads_gmv_board', 'ADS',
 'NOT_DELETE', NOW(), NOW()),
('std_nm_ads_02', 1, 'ok', 'default', '平台预置·应用层带域',
 'ads_<域>_<场景>', 'ads_trade.ads_order_funnel', 'ADS',
 'NOT_DELETE', NOW(), NOW()),
('std_nm_dim_01', 1, 'ok', 'default', '平台预置·维度',
 'dim_<实体>', 'dim.dim_sku', 'DIM',
 'NOT_DELETE', NOW(), NOW()),
('std_nm_dim_02', 1, 'ok', 'default', '平台预置·缓慢变化维可加 scd',
 'dim_<实体>_scd2', 'dim.dim_user_scd2', 'DIM',
 'NOT_DELETE', NOW(), NOW()),

-- 资产编码（门户 asset_code，小写+下划线）
('std_nm_asset_01', 1, 'ok', 'default', '平台预置·资产编码',
 '{layer}_{domain}_{entity}[_粒度]', 'dwd_trade_order_detail_d', '其他',
 'NOT_DELETE', NOW(), NOW()),

-- 作业 / 调度
('std_nm_job_01', 1, 'ok', 'default', '平台预置·批作业',
 'job_<层>_<表>_<动作>', 'job_dwd_order_detail_clean', '任务',
 'NOT_DELETE', NOW(), NOW()),
('std_nm_job_02', 1, 'ok', 'default', '平台预置·流作业',
 'stream_<层>_<表>_<动作>', 'stream_dwd_order_detail_upsert', '任务',
 'NOT_DELETE', NOW(), NOW()),
('std_nm_job_03', 1, 'ok', 'default', '平台预置·DataX/同步',
 'sync_<源>_<目标>_<对象>', 'sync_mysql_iceberg_s_order', '任务',
 'NOT_DELETE', NOW(), NOW()),

-- 消息
('std_nm_mq_01', 1, 'ok', 'default', '平台预置·Kafka Topic',
 'topic_<域>_<事件>', 'topic_trade_order_paid', '消息',
 'NOT_DELETE', NOW(), NOW()),
('std_nm_mq_02', 1, 'ok', 'default', '平台预置·CDC 主题',
 'cdc.<库>.<表>', 'cdc.trade.s_order', '消息',
 'NOT_DELETE', NOW(), NOW()),
('std_nm_mq_03', 1, 'ok', 'default', '平台预置·死信',
 'topic_<域>_<事件>_dlq', 'topic_trade_order_paid_dlq', '消息',
 'NOT_DELETE', NOW(), NOW()),

-- 临时 / 视图
('std_nm_tmp_01', 1, 'ok', 'default', '平台预置·临时表须可过期',
 'tmp_<层>_<用途>_<日期>', 'tmp_dwd_order_backfill_20260901', '临时',
 'NOT_DELETE', NOW(), NOW()),
('std_nm_view_01', 1, 'ok', 'default', '平台预置·逻辑视图',
 'view_<层>_<实体>', 'view_dwd_order_detail_v', '视图',
 'NOT_DELETE', NOW(), NOW()),

-- 服务 / 指标 / 质量
('std_nm_api_01', 1, 'ok', 'default', '平台预置·数据服务',
 'api_<域>_<资源>[_动作]', 'api_trade_order_list', '接口',
 'NOT_DELETE', NOW(), NOW()),
('std_nm_metric_01', 1, 'ok', 'default', '平台预置·指标编码',
 'metric_<域>_<指标>_<周期>', 'metric_trade_gmv_1d', '指标',
 'NOT_DELETE', NOW(), NOW()),
('std_nm_dq_01', 1, 'ok', 'default', '平台预置·质量规则',
 'dq_<表>_<规则>', 'dq_dwd_order_detail_null_check', '质量',
 'NOT_DELETE', NOW(), NOW()),

-- 湖表 FQTN / 搜索索引（可选约定）
('std_nm_lake_01', 1, 'ok', 'default', '平台预置·Iceberg 全限定名',
 '<catalog>.<layer>_<domain>.<table>', 'iceberg.dwd_trade.dwd_order_detail_d', '其他',
 'NOT_DELETE', NOW(), NOW()),
('std_nm_es_01', 1, 'ok', 'default', '平台预置·检索索引',
 'idx_<域>_<实体>', 'idx_trade_order', '其他',
 'NOT_DELETE', NOW(), NOW());
