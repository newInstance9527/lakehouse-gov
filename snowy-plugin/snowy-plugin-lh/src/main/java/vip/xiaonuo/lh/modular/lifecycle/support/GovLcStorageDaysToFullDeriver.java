package vip.xiaonuo.lh.modular.lifecycle.support;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.engine.VictoriaMetricsClient;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcStorageChangePoint;
import vip.xiaonuo.lh.modular.lifecycle.mapper.GovLcStorageChangePointMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日批派生 {@code lh_table_storage_days_to_full{quantile}}：
 * 从 VM 拉 {@code lh_table_storage_bytes{kind="total"}} 日序列 + 变更点截断 → 分段线性 → 写回同批 import。
 * 现网未配 VM / 样本 &lt;15 天则跳过（不写点）。
 */
@Component
public class GovLcStorageDaysToFullDeriver {

    private static final String NOT_DELETE = "NOT_DELETE";

    @Resource
    private VictoriaMetricsClient victoriaMetricsClient;
    @Resource
    private LhProperties lhProperties;
    @Resource
    private GovLcStorageChangePointMapper changePointMapper;

    /**
     * @param profileRows 画像行（需 collectStatus / tableFqn / totalBytes / reclaimableBytes）
     * @param dayTsMs     当日 00:00 UTC
     * @return summary + 追加的 Prometheus 行
     */
    public Map<String, Object> deriveAndFormat(String ws, List<Map<String, Object>> profileRows, long dayTsMs) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> lines = new ArrayList<>();
        int written = 0;
        int skipped = 0;
        Map<String, Integer> skipReasons = new LinkedHashMap<>();

        long capacity = capacityBytes();
        double reclaimConf = reclaimConfidence();
        if (capacity <= 0) {
            out.put("lines", lines);
            out.put("written", 0);
            out.put("skipped", profileRows == null ? 0 : profileRows.size());
            out.put("reason", "NO_CAPACITY");
            return out;
        }

        LocalDate day = Instant.ofEpochMilli(dayTsMs).atZone(ZoneOffset.UTC).toLocalDate();
        long todayEpochDay = day.toEpochDay();
        List<Long> wsChangeDays = loadChangePointEpochDays(ws, null);
        long lookbackSec = (GovLcStorageDaysToFull.MAX_WINDOW_DAYS + 5L) * 86_400L;
        long endSec = dayTsMs / 1000L + 86_400L - 1;
        long startSec = Math.max(0, endSec - lookbackSec);

        if (profileRows != null) {
            for (Map<String, Object> row : profileRows) {
                String status = String.valueOf(row.get("collectStatus"));
                if ("failed".equals(status)) {
                    skipped++;
                    bump(skipReasons, "FAILED_ISOLATION");
                    continue;
                }
                String fqn = StrUtil.trimToEmpty(String.valueOf(row.get("tableFqn")));
                if (StrUtil.isBlank(fqn) || "null".equals(fqn)) {
                    skipped++;
                    bump(skipReasons, "NO_FQTN");
                    continue;
                }
                long total = longVal(row.get("totalBytes"));
                long reclaimable = longVal(row.get("reclaimableBytes"));
                String layer = row.get("layer") == null ? null : String.valueOf(row.get("layer"));

                List<GovLcStorageDaysToFull.Point> history = loadHistory(ws, fqn, startSec, endSec);
                history = mergeToday(history, todayEpochDay, total);
                List<Long> changeDays = new ArrayList<>(wsChangeDays);
                changeDays.addAll(loadChangePointEpochDays(ws, fqn));

                GovLcStorageDaysToFull.Result result = GovLcStorageDaysToFull.compute(
                        history, changeDays, capacity, total, reclaimable, reclaimConf);
                if (!result.available() || result.p50Days() == null || result.p95Days() == null) {
                    skipped++;
                    bump(skipReasons, StrUtil.blankToDefault(result.reason(), "SKIP"));
                    continue;
                }
                lines.addAll(GovLcStorageMetricsFormatter.formatDaysToFull(
                        fqn, ws, layer, result.p50Days(), result.p95Days(), dayTsMs));
                written++;
                row.put("daysToFullP50", result.p50Days());
                row.put("daysToFullP95", result.p95Days());
                row.put("daysToFullR2", result.r2());
                row.put("daysToFullUnstable", result.unstable());
                row.put("daysToFullSamples", result.sampleCount());
            }
        }

        out.put("lines", lines);
        out.put("written", written);
        out.put("skipped", skipped);
        out.put("skipReasons", skipReasons);
        out.put("capacityBytes", capacity);
        out.put("vmConfigured", victoriaMetricsClient.configured());
        return out;
    }

    private List<GovLcStorageDaysToFull.Point> loadHistory(String ws, String fqn, long startSec, long endSec) {
        if (!victoriaMetricsClient.configured()) {
            return new ArrayList<>();
        }
        String promQl = "lh_table_storage_bytes{fqtn=\"" + escProm(fqn)
                + "\",ws=\"" + escProm(ws) + "\",kind=\"total\"}";
        List<GovLcStorageDaysToFull.Point> out = new ArrayList<>();
        for (VictoriaMetricsClient.RangePoint p : victoriaMetricsClient.queryRangePoints(
                promQl, startSec, endSec, "1d")) {
            out.add(new GovLcStorageDaysToFull.Point(p.epochDay(), p.value()));
        }
        return out;
    }

    private static List<GovLcStorageDaysToFull.Point> mergeToday(
            List<GovLcStorageDaysToFull.Point> history, long todayEpochDay, long totalBytes) {
        List<GovLcStorageDaysToFull.Point> out = new ArrayList<>(history == null ? List.of() : history);
        boolean found = false;
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).epochDay() == todayEpochDay) {
                out.set(i, new GovLcStorageDaysToFull.Point(todayEpochDay, totalBytes));
                found = true;
                break;
            }
        }
        if (!found) {
            out.add(new GovLcStorageDaysToFull.Point(todayEpochDay, totalBytes));
        }
        return out;
    }

    private List<Long> loadChangePointEpochDays(String ws, String fqtnOrNull) {
        Date from = java.sql.Date.valueOf(LocalDate.now(ZoneOffset.UTC).minusDays(
                GovLcStorageDaysToFull.MAX_WINDOW_DAYS + 30));
        var q = new QueryWrapper<GovLcStorageChangePoint>().lambda()
                .eq(GovLcStorageChangePoint::getWs, ws)
                .eq(GovLcStorageChangePoint::getDeleteFlag, NOT_DELETE)
                .ge(GovLcStorageChangePoint::getDt, from);
        if (StrUtil.isNotBlank(fqtnOrNull)) {
            q.eq(GovLcStorageChangePoint::getScopeType, "table")
                    .eq(GovLcStorageChangePoint::getScopeKey, fqtnOrNull);
        } else {
            q.and(w -> w.isNull(GovLcStorageChangePoint::getScopeType)
                    .or().eq(GovLcStorageChangePoint::getScopeType, "ws")
                    .or().eq(GovLcStorageChangePoint::getScopeType, "bucket"));
        }
        List<GovLcStorageChangePoint> rows = changePointMapper.selectList(q);
        List<Long> days = new ArrayList<>();
        for (GovLcStorageChangePoint cp : rows) {
            if (cp.getDt() == null) {
                continue;
            }
            LocalDate d = cp.getDt() instanceof java.sql.Date sql
                    ? sql.toLocalDate()
                    : Instant.ofEpochMilli(cp.getDt().getTime()).atZone(ZoneOffset.UTC).toLocalDate();
            days.add(d.toEpochDay());
        }
        return days;
    }

    private long capacityBytes() {
        if (lhProperties.getLifecycle() == null) {
            return 20L * 1024 * 1024 * 1024 * 1024;
        }
        return Math.max(0, lhProperties.getLifecycle().getForecastDefaultCapacityBytes());
    }

    private double reclaimConfidence() {
        if (lhProperties.getLifecycle() == null) {
            return GovLcStorageDaysToFull.RECLAIM_CONFIDENCE;
        }
        double v = lhProperties.getLifecycle().getForecastReclaimConfidence();
        if (v <= 0 || v > 1) {
            return GovLcStorageDaysToFull.RECLAIM_CONFIDENCE;
        }
        return v;
    }

    private static void bump(Map<String, Integer> map, String key) {
        map.merge(key, 1, Integer::sum);
    }

    private static long longVal(Object v) {
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    static String escProm(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
