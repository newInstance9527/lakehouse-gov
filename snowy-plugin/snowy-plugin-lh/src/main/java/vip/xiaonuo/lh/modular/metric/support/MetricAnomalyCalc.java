package vip.xiaonuo.lh.modular.metric.support;

import cn.hutool.core.util.StrUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 日波动：从查询结果抽标量；相对前日算 change_pct；超阈值标 anomaly。
 */
public final class MetricAnomalyCalc {

    private MetricAnomalyCalc() {
    }

    /**
     * 取首行 {@code metric_value}，否则第一个可解析数值列。
     */
    public static BigDecimal extractScalar(List<Map<String, Object>> rows, List<String> columns) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        if (row == null || row.isEmpty()) {
            return null;
        }
        Object preferred = firstIgnoreCase(row, "metric_value", "v", "value");
        BigDecimal fromPref = toDecimal(preferred);
        if (fromPref != null) {
            return fromPref;
        }
        if (columns != null) {
            for (String col : columns) {
                BigDecimal d = toDecimal(row.get(col));
                if (d != null) {
                    return d;
                }
            }
        }
        for (Object v : row.values()) {
            BigDecimal d = toDecimal(v);
            if (d != null) {
                return d;
            }
        }
        return null;
    }

    /**
     * @return 相对变动百分比；prev 为 0/null 时返回 null
     */
    public static BigDecimal changePct(BigDecimal current, BigDecimal prev) {
        if (current == null || prev == null) {
            return null;
        }
        if (prev.compareTo(BigDecimal.ZERO) == 0) {
            return current.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP)
                    : null;
        }
        return current.subtract(prev)
                .multiply(BigDecimal.valueOf(100))
                .divide(prev.abs(), 6, RoundingMode.HALF_UP);
    }

    public static boolean isAnomaly(BigDecimal changePct, BigDecimal thresholdPct) {
        if (changePct == null || thresholdPct == null) {
            return false;
        }
        return changePct.abs().compareTo(thresholdPct.abs()) >= 0;
    }

    private static Object firstIgnoreCase(Map<String, Object> row, String... keys) {
        for (String want : keys) {
            for (Map.Entry<String, Object> e : row.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(want)) {
                    return e.getValue();
                }
            }
        }
        return null;
    }

    private static BigDecimal toDecimal(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof BigDecimal bd) {
            return bd;
        }
        if (o instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        String s = StrUtil.trim(String.valueOf(o));
        if (StrUtil.isBlank(s) || "null".equalsIgnoreCase(s)) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (Exception e) {
            return null;
        }
    }

    public static String severity(BigDecimal changePct, BigDecimal thresholdPct) {
        if (!isAnomaly(changePct, thresholdPct)) {
            return "normal";
        }
        double abs = changePct == null ? 0 : changePct.abs().doubleValue();
        double thr = thresholdPct == null ? 20 : thresholdPct.abs().doubleValue();
        if (abs >= thr * 2) {
            return "critical";
        }
        return "warning";
    }

    public static String summarize(boolean anomaly, BigDecimal changePct, BigDecimal thresholdPct) {
        if (!anomaly) {
            return "日波动在阈值内";
        }
        String pct = changePct == null ? "?" : changePct.setScale(2, RoundingMode.HALF_UP).toPlainString();
        String thr = thresholdPct == null ? "?" : thresholdPct.setScale(2, RoundingMode.HALF_UP).toPlainString();
        return String.format(Locale.ROOT, "日波动 %s%% 超过阈值 %s%%", pct, thr);
    }
}
