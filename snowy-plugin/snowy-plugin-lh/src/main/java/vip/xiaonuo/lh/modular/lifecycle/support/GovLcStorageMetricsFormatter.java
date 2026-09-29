package vip.xiaonuo.lh.modular.lifecycle.support;

import cn.hutool.core.util.StrUtil;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 组装 {@code lh_table_storage_*} / {@code lh_ws_storage_*} Prometheus 文本行，
 * 供 VictoriaMetrics {@code /api/v1/import/prometheus}。
 * 时间戳对齐当日 00:00 UTC，同日重跑覆盖同一点（日切幂等）。
 * 表级标签含 {@code owner}（目录认责；空则 {@code unassigned}），供夜莺按 owner 路由。
 */
public final class GovLcStorageMetricsFormatter {

    public static final String OWNER_UNASSIGNED = "unassigned";

    private GovLcStorageMetricsFormatter() {
    }

    public record Sample(
            String fqtn,
            String ws,
            String layer,
            String owner,
            long activeBytes,
            long totalBytes,
            long reclaimableBytes,
            long activeFiles,
            long avgActiveFileBytes,
            double smallFileRatio,
            Integer snapshotCount,
            Integer partitionCount
    ) {
    }

    /** 当日 00:00 UTC 的 epoch 毫秒。 */
    public static long dayCutEpochMs(Instant now) {
        LocalDate day = now.atZone(ZoneOffset.UTC).toLocalDate();
        return day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    public static List<String> format(Sample sample, long timestampMs) {
        List<String> lines = new ArrayList<>();
        String base = labels(sample.fqtn(), sample.ws(), sample.layer(), sample.owner());
        lines.add(gauge("lh_table_storage_bytes", base + ",kind=\"active\"", sample.activeBytes(), timestampMs));
        lines.add(gauge("lh_table_storage_bytes", base + ",kind=\"total\"", sample.totalBytes(), timestampMs));
        lines.add(gauge("lh_table_storage_bytes", base + ",kind=\"reclaimable\"", sample.reclaimableBytes(), timestampMs));
        lines.add(gauge("lh_table_storage_files", base + ",kind=\"active\"", sample.activeFiles(), timestampMs));
        lines.add(gauge("lh_table_storage_avg_file_bytes", base, sample.avgActiveFileBytes(), timestampMs));
        lines.add(gauge("lh_table_storage_small_file_ratio", base, sample.smallFileRatio(), timestampMs));
        if (sample.snapshotCount() != null) {
            lines.add(gauge("lh_table_storage_snapshots", base, sample.snapshotCount(), timestampMs));
        }
        if (sample.partitionCount() != null) {
            lines.add(gauge("lh_table_storage_partitions", base, sample.partitionCount(), timestampMs));
        }
        return lines;
    }

    /**
     * 派生指标 {@code lh_table_storage_days_to_full{quantile="p50|p95"}}；
     * 仅在预测可用时写出（样本不足 / 斜率非正不写点，避免污染告警）。
     */
    public static List<String> formatDaysToFull(String fqtn, String ws, String layer, String owner,
                                                double p50Days, double p95Days, long timestampMs) {
        List<String> lines = new ArrayList<>(2);
        String base = labels(fqtn, ws, layer, owner);
        lines.add(gauge("lh_table_storage_days_to_full", base + ",quantile=\"p50\"", p50Days, timestampMs));
        lines.add(gauge("lh_table_storage_days_to_full", base + ",quantile=\"p95\"", p95Days, timestampMs));
        return lines;
    }

    /** 空间配额投影：激活夜莺 {@code LhWorkspaceStorageQuotaOver80Pct}。 */
    public static List<String> formatWsStorage(String ws, String owner, long usedBytes, long quotaBytes,
                                               long timestampMs) {
        List<String> lines = new ArrayList<>(2);
        String base = "ws=\"" + esc(ws) + "\",owner=\"" + esc(blankOwner(owner)) + "\"";
        lines.add(gauge("lh_ws_storage_used_bytes", base, Math.max(0L, usedBytes), timestampMs));
        lines.add(gauge("lh_ws_storage_quota_bytes", base, Math.max(0L, quotaBytes), timestampMs));
        return lines;
    }

    public static String joinBody(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        return String.join("\n", lines) + "\n";
    }

    private static String labels(String fqtn, String ws, String layer, String owner) {
        return "fqtn=\"" + esc(fqtn) + "\",ws=\"" + esc(ws) + "\",layer=\"" + esc(blankLayer(layer))
                + "\",owner=\"" + esc(blankOwner(owner)) + "\"";
    }

    private static String blankLayer(String layer) {
        return StrUtil.blankToDefault(layer, "unknown");
    }

    static String blankOwner(String owner) {
        return StrUtil.blankToDefault(StrUtil.trim(owner), OWNER_UNASSIGNED);
    }

    private static String gauge(String name, String labels, double value, long ts) {
        return name + "{" + labels + "} " + formatNumber(value) + " " + ts;
    }

    private static String formatNumber(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "0";
        }
        if (value == Math.rint(value) && Math.abs(value) < Long.MAX_VALUE) {
            return Long.toString((long) value);
        }
        return String.format(Locale.ROOT, "%.6f", value);
    }

    static String esc(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"");
    }
}
