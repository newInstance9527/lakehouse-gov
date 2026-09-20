package vip.xiaonuo.lh.modular.export.service;

import java.util.List;
import java.util.Map;

/**
 * 出湖与回流运营台（聚合 apply lake_export + ETL sink）
 */
public interface ExportBoardService {

    Map<String, Object> summary(String ws);

    List<Map<String, Object>> jobs(String ws, String status, String q);

    Map<String, Object> audit(String ws, String ticketNo);
}
