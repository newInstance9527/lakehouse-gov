package vip.xiaonuo.lh.modular.quality.support;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 质量运行 → VictoriaMetrics：批 {@code lh_dq_rule_*}；流 {@code lh_dq_stream_*}。
 */
public final class GovDqMetricsFormatter {

    private GovDqMetricsFormatter() {
    }

    public static List<String> format(String ruleCode, String tableName, String ws, String severity,
                                      boolean pass, boolean blocked, Double okPct, long timestampMs) {
        List<String> lines = new ArrayList<>(3);
        String base = labels(ruleCode, tableName, ws, severity, null);
        lines.add(gauge("lh_dq_rule_pass", base, pass ? 1 : 0, timestampMs));
        lines.add(gauge("lh_dq_rule_blocked", base, blocked ? 1 : 0, timestampMs));
        if (okPct != null && !okPct.isNaN() && !okPct.isInfinite()) {
            lines.add(gauge("lh_dq_ok_pct", base, okPct, timestampMs));
        }
        return lines;
    }

    /**
     * Flink 流式探针写点（夜莺即时告警；不写 blocked）。
     */
    public static List<String> formatStream(String ruleCode, String tableName, String ws, String severity,
                                            String jobId, boolean pass, Double okPct, Double failRatio,
                                            Long lagMs, long timestampMs) {
        List<String> lines = new ArrayList<>(4);
        String base = labels(ruleCode, tableName, ws, severity, jobId);
        lines.add(gauge("lh_dq_stream_pass", base, pass ? 1 : 0, timestampMs));
        if (okPct != null && !okPct.isNaN() && !okPct.isInfinite()) {
            lines.add(gauge("lh_dq_stream_ok_pct", base, okPct, timestampMs));
        }
        if (failRatio != null && !failRatio.isNaN() && !failRatio.isInfinite()) {
            lines.add(gauge("lh_dq_stream_fail_ratio", base, failRatio, timestampMs));
        }
        if (lagMs != null && lagMs >= 0) {
            lines.add(gauge("lh_dq_stream_lag_ms", base, lagMs.doubleValue(), timestampMs));
        }
        return lines;
    }

    public static String joinBody(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        return String.join("\n", lines) + "\n";
    }

    private static String labels(String ruleCode, String tableName, String ws, String severity, String jobId) {
        StringBuilder sb = new StringBuilder();
        sb.append("rule_code=\"").append(esc(ruleCode)).append("\",table=\"").append(esc(tableName))
                .append("\",ws=\"").append(esc(ws)).append("\",severity=\"").append(esc(severity)).append("\"");
        if (StrUtil.isNotBlank(jobId)) {
            sb.append(",job=\"").append(esc(jobId)).append("\"");
        }
        return sb.toString();
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
