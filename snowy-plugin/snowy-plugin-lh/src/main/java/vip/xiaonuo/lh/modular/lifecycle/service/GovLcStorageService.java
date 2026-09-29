package vip.xiaonuo.lh.modular.lifecycle.service;

import java.util.List;
import java.util.Map;

/**
 * 存储趋势只读度量（doc/存储趋势.md）
 * <p>
 * 时序 SoT = VictoriaMetrics {@code lh_table_storage_*}；瞬时投影 = {@code gov_lc_table_stat}（画像日批）。
 * 无水位时返回空列表 / 零值，禁止种子与启发式假数。
 */
public interface GovLcStorageService {

    Map<String, Object> summary(String ws, String range);

    Map<String, Object> trend(String ws, String range, String group);

    Map<String, Object> tables(String ws, String range, String layer, String filter,
                               String sort, String order, Integer page, Integer size);

    Map<String, Object> tableDetail(String ws, String fqtn, String range);

    /** 桶水位；返回 `{ source, vmConfigured, list }`，有点时 `source` 以 `vm:` 开头 */
    Map<String, Object> buckets(String ws);

    List<Map<String, Object>> advice(String ws);

    Map<String, Object> showback(String ws, String range, String group);

    /** 存储日报导出（CSV 文本）；无表时 content 为空串 */
    Map<String, Object> reportExport(String ws, String range, String format);
}
