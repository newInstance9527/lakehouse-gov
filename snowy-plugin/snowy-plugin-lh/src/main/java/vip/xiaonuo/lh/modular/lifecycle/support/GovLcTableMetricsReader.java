package vip.xiaonuo.lh.modular.lifecycle.support;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.core.engine.VictoriaMetricsClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 读 VictoriaMetrics {@code lh_table_storage_*}，供 overview / storage/trend / forecast / tables/detail 曲线。
 * 未配 VM 或无点时返回空（调用方空态，禁止种子回落）。
 */
@Component
public class GovLcTableMetricsReader {

    public record DailyCaliber(LocalDate date, long totalBytes, long activeBytes, long reclaimableBytes) {
    }

    public record ForecastSnap(Double p50DaysToFull, Double p95DaysToFull, int sampleSeries, String source) {
    }

    @Resource
    private VictoriaMetricsClient victoriaMetricsClient;

    public boolean available() {
        return victoriaMetricsClient.configured();
    }

    /**
     * 空间聚合日序列（物理/活跃/可回收）。无 VM 或无点 → 空列表。
     */
    public List<DailyCaliber> dailyAggregate(String ws, int days) {
        if (!available() || days <= 0) {
            return List.of();
        }
        long endSec = Instant.now().getEpochSecond();
        long startSec = Math.max(0, endSec - (long) days * 86_400L);
        String wsSel = wsFilter(ws);
        Map<Long, long[]> byDay = new LinkedHashMap<>();
        mergeKind(byDay, "total", 0, wsSel, startSec, endSec);
        mergeKind(byDay, "active", 1, wsSel, startSec, endSec);
        mergeKind(byDay, "reclaimable", 2, wsSel, startSec, endSec);
        if (byDay.isEmpty()) {
            return List.of();
        }
        List<DailyCaliber> out = new ArrayList<>();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (int i = days - 1; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            long epochDay = d.toEpochDay();
            long[] v = byDay.get(epochDay);
            if (v == null) {
                continue;
            }
            long total = v[0];
            long active = v[1];
            long reclaim = v[2];
            if (total <= 0 && active > 0) {
                total = active + Math.max(0, reclaim);
            }
            out.add(new DailyCaliber(d, total, active, reclaim));
        }
        return out;
    }

    /**
     * 单表日曲线（kind=total/active/reclaimable）。
     */
    public List<DailyCaliber> dailyForTable(String ws, String fqtn, int days) {
        if (!available() || StrUtil.isBlank(fqtn) || days <= 0) {
            return List.of();
        }
        long endSec = Instant.now().getEpochSecond();
        long startSec = Math.max(0, endSec - (long) days * 86_400L);
        String base = "fqtn=\"" + esc(fqtn.trim()) + "\""
                + (StrUtil.isNotBlank(ws) ? ",ws=\"" + esc(ws.trim()) + "\"" : "");
        Map<Long, long[]> byDay = new LinkedHashMap<>();
        mergeQuery(byDay, "lh_table_storage_bytes{" + base + ",kind=\"total\"}", 0, startSec, endSec);
        mergeQuery(byDay, "lh_table_storage_bytes{" + base + ",kind=\"active\"}", 1, startSec, endSec);
        mergeQuery(byDay, "lh_table_storage_bytes{" + base + ",kind=\"reclaimable\"}", 2, startSec, endSec);
        List<DailyCaliber> out = new ArrayList<>();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (int i = days - 1; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            long[] v = byDay.get(d.toEpochDay());
            if (v == null) {
                continue;
            }
            out.add(new DailyCaliber(d, v[0], v[1], v[2]));
        }
        return out;
    }

    /**
     * 读日批写回的 days_to_full；取最紧（最小）p50/p95。无点 → null。
     */
    public ForecastSnap forecast(String ws) {
        if (!available()) {
            return new ForecastSnap(null, null, 0, "vm-unconfigured");
        }
        String wsSel = wsFilter(ws);
        List<VictoriaMetricsClient.InstantSample> p50 =
                victoriaMetricsClient.queryInstant("lh_table_storage_days_to_full{quantile=\"p50\"" + wsSel + "}");
        List<VictoriaMetricsClient.InstantSample> p95 =
                victoriaMetricsClient.queryInstant("lh_table_storage_days_to_full{quantile=\"p95\"" + wsSel + "}");
        Double minP50 = minPositive(p50);
        Double minP95 = minPositive(p95);
        int n = Math.max(p50.size(), p95.size());
        return new ForecastSnap(minP50, minP95, n, "lh_table_storage_days_to_full");
    }

    private void mergeKind(Map<Long, long[]> byDay, String kind, int idx, String wsSel, long startSec, long endSec) {
        String q = "sum(lh_table_storage_bytes{kind=\"" + kind + "\"" + wsSel + "})";
        mergeQuery(byDay, q, idx, startSec, endSec);
    }

    private void mergeQuery(Map<Long, long[]> byDay, String promQl, int idx, long startSec, long endSec) {
        for (VictoriaMetricsClient.RangePoint p : victoriaMetricsClient.queryRangePoints(
                promQl, startSec, endSec, "1d")) {
            long[] slot = byDay.computeIfAbsent(p.epochDay(), k -> new long[3]);
            slot[idx] = p.value();
        }
    }

    private static Double minPositive(List<VictoriaMetricsClient.InstantSample> samples) {
        Double min = null;
        if (samples == null) {
            return null;
        }
        for (VictoriaMetricsClient.InstantSample s : samples) {
            if (s == null || s.value() <= 0 || Double.isNaN(s.value())) {
                continue;
            }
            if (min == null || s.value() < min) {
                min = s.value();
            }
        }
        return min;
    }

    private static String wsFilter(String ws) {
        if (StrUtil.isBlank(ws)) {
            return "";
        }
        return ",ws=\"" + esc(ws.trim()) + "\"";
    }

    static String esc(String raw) {
        return StrUtil.blankToDefault(raw, "").replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static String layerOf(String fqtn) {
        if (StrUtil.isBlank(fqtn)) {
            return "OTHER";
        }
        String low = fqtn.toLowerCase(Locale.ROOT);
        if (low.contains(".ods_") || low.startsWith("ods_") || low.contains(".ods.")) {
            return "ODS";
        }
        if (low.contains(".dwd_") || low.startsWith("dwd_")) {
            return "DWD";
        }
        if (low.contains(".dws_") || low.startsWith("dws_")) {
            return "DWS";
        }
        if (low.contains(".ads_") || low.startsWith("ads_") || low.contains(".ads.")) {
            return "ADS";
        }
        if (low.contains(".dim_") || low.startsWith("dim_")) {
            return "DIM";
        }
        return "OTHER";
    }
}
