package vip.xiaonuo.lh.modular.lifecycle.service;

import java.util.List;
import java.util.Map;

/**
 * 存储趋势只读度量（doc/存储趋势.md 第一刀）
 * <p>
 * 时序正式源为 VictoriaMetrics；P0 用 {@code gov_lc_table_stat} 种子派生三口径。
 */
public interface GovLcStorageService {

    Map<String, Object> summary(String ws, String range);

    Map<String, Object> trend(String ws, String range, String group);

    Map<String, Object> tables(String ws, String range, String layer, String filter,
                               String sort, String order, Integer page, Integer size);

    Map<String, Object> tableDetail(String ws, String fqtn, String range);

    List<Map<String, Object>> buckets(String ws);

    List<Map<String, Object>> advice(String ws);

    Map<String, Object> showback(String ws, String range, String group);
}
