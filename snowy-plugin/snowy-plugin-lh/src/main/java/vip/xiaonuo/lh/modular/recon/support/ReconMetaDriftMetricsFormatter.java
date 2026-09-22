package vip.xiaonuo.lh.modular.recon.support;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 元数据漂移 → VictoriaMetrics：{@code lh_meta_drift_open}。
 */
public final class ReconMetaDriftMetricsFormatter {

    private ReconMetaDriftMetricsFormatter() {
    }

    public static List<String> formatOpenCount(String ws, String severity, String driftType,
                                               long openCount, long timestampMs) {
        List<String> lines = new ArrayList<>(1);
        String labels = "ws=\"" + esc(ws) + "\",severity=\"" + esc(severity)
                + "\",drift_type=\"" + esc(driftType) + "\"";
        long tsSec = Math.max(0L, timestampMs / 1000L);
        lines.add("lh_meta_drift_open{" + labels + "} " + openCount + " " + tsSec);
        return lines;
    }

    public static String joinBody(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        return String.join("\n", lines) + "\n";
    }

    private static String esc(String s) {
        return StrUtil.blankToDefault(s, "").replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
