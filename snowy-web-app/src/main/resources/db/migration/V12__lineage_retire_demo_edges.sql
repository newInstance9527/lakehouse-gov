-- 下线 V8 交易域 GMV 演示字段边（门户以真实 ETL 发布边为准）
UPDATE `gov_lineage_field_edge`
SET `delete_flag` = 'DELETED',
    `status` = 'inactive',
    `update_time` = NOW()
WHERE `id` IN ('le01','le02','le03','le04','le05','le06','le07')
   OR `etl_job_id` IN ('etl_dwd_order','etl_dws_order','etl_ads_gmv');
