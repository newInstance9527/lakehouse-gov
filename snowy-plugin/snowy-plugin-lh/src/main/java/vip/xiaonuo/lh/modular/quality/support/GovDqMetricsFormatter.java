package vip.xiaonuo.lh.modular.quality.support;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 质量运行 → VictoriaMetrics：{@code lh_dq_rule_pass} / {@code lh_dq_rule_blocked} / {@code lh_dq_ok_pct}。
 */
public final class GovDqMetricsFormatter {

    private GovDqMetricsFormatter() {
    }

    public static List<String> format(String ruleCode, String tableName, String ws, String severity,
                                      boolean pass, boolean blocked, Double okPct, long timestampMs) {
        List<String> lines = new ArrayList<>(3);
        String base = labels(ruleCode, tableName, ws, severity);
        lines.add(gauge("lh_dq_rule_pass", base, pass ? 1 : 0, timestampMs));
        lines.add(gauge("lh_dq_rule_blocked", base, blocked ? 1 : 0, timestampMs));
        if (okPct != null && !okPct.isNaN() && !okPct.isInfinite()) {
            lines.add(gauge("lh_dq_ok_pct", base, okPct, timestampMs));
        }
        return lines;
    }

    public static String joinBody(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        return String.join("\n", lines) + "\n";
    }

    private static String labels(String ruleCode, String tableName, String ws, String severity) {
        return "rule_code=\"" + esc(ruleCode) + "\",table=\"" + esc(tableName)
                + "\",ws=\"" + esc(ws) + "\",severity=\"" + esc(severity) + "\"";
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
