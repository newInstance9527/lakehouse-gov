package vip.xiaonuo.lh.modular.export.service;

import java.util.List;
import java.util.Map;

/**
 * 出湖与回流运营台（聚合 apply lake_export + ETL sink）
 */
public interface ExportBoardService {

    Map<String, Object> summary(String ws);

    List<Map<String, Object>> jobs(String ws, String status, String q);

    /** 出库审计（A7 正式表 gov_export_audit；空时回落 soft 摘要） */
    Map<String, Object> audit(String ws, String ticketNo);

    /** 手动触发到期停作业（A6；与定时任务同逻辑） */
    Map<String, Object> expireDue();
}
