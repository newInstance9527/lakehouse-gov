package vip.xiaonuo.lh.modular.metric.support;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 指标波动采样 → VictoriaMetrics：{@code lh_metric_value} / {@code lh_metric_change_pct} / {@code lh_metric_anomaly}。
 */
public final class GovMetricMetricsFormatter {

    private GovMetricMetricsFormatter() {
    }

    public static List<String> format(String metricCode, String ws, String ver,
                                      Double value, Double changePct, boolean anomaly,
                                      long timestampMs) {
        List<String> lines = new ArrayList<>(3);
        String base = labels(metricCode, ws, ver);
        if (value != null && !value.isNaN() && !value.isInfinite()) {
            lines.add(gauge("lh_metric_value", base, value, timestampMs));
        }
        if (changePct != null && !changePct.isNaN() && !changePct.isInfinite()) {
            lines.add(gauge("lh_metric_change_pct", base, changePct, timestampMs));
        }
        lines.add(gauge("lh_metric_anomaly", base, anomaly ? 1 : 0, timestampMs));
        return lines;
    }

    public static String joinBody(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        return String.join("\n", lines) + "\n";
    }

    private static String labels(String metricCode, String ws, String ver) {
        return "metric_code=\"" + esc(metricCode) + "\",ws=\"" + esc(ws)
                + "\",ver=\"" + esc(StrUtil.blankToDefault(ver, "")) + "\"";
    }

    private static String gauge(String name, String labels, double value, long tsMs) {
        long tsSec = Math.max(0L, tsMs / 1000L);
        return name + "{" + labels + "} " + trimNum(value) + " " + tsSec;
    }

    private static String trimNum(double v) {
        if (v == (long) v) {
            return Long.toString((long) v);
        }
        return String.format(Locale.ROOT, "%.6f", v);
    }

    private static String esc(String s) {
        return StrUtil.blankToDefault(s, "").replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
