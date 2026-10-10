-- F2/M3：种子物化登记（M-0001@v3 → CK 热表）；recon_ok=1 供 prefer=hot 联调
-- 目标表需现网/联调环境自行建表灌数；无表时 Exec 会 soft-fail 回退 Trino

INSERT INTO `gov_metric_materialize`
(`id`,`revision`,`status`,`ws`,`remark`,`metric_code`,`ver`,`engine`,`target_table`,`grain_json`,`job_ref`,`recon_ok`,`delete_flag`,`create_time`,`update_time`)
VALUES
('gmm01',1,'active','default','F2/M3 seed hot path','M-0001','v3','clickhouse','ads.metric_m_0001_d','["dt"]','seed.metric.mat.m0001',1,'NOT_DELETE',NOW(),NOW())
ON DUPLICATE KEY UPDATE
  `target_table`=VALUES(`target_table`),
  `recon_ok`=VALUES(`recon_ok`),
  `status`=VALUES(`status`),
  `update_time`=NOW();
