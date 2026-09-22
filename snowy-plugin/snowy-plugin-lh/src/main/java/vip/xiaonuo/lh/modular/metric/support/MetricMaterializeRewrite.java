package vip.xiaonuo.lh.modular.metric.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 物化命中改写：读 {@code gov_metric_materialize.target_table}，按时间窗生成 CK / Trino 只读 SQL。
 */
public final class MetricMaterializeRewrite {

    private static final Pattern SAFE_TABLE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*){0,2}$");

    private MetricMaterializeRewrite() {
    }

    /**
     * ClickHouse 方言：{@code SELECT * FROM db.table WHERE dt = toDate('…') LIMIT n}。
     */
    public static String rewriteClickHouse(String targetTable, String timeWindow,
                                           List<String> grainKeys, String grainJson,
                                           String dt, String from, String to, int maxRows) {
        String table = assertSafeTable(targetTable);
        List<String> grains = resolveGrains(grainKeys, grainJson);
        String timeCol = MetricParamBinder.pickTimeCol(grains);
        String where = ckTimePredicate(timeWindow, timeCol, dt, from, to);
        int n = Math.max(1, Math.min(maxRows, 10000));
        return "SELECT * FROM " + table + " WHERE " + where + " LIMIT " + n;
    }

    /**
     * Trino 方言物化改写（ADS Iceberg）；本轮 hot 主路径用 CK，Trino 侧可选命中。
     */
    public static String rewriteTrino(String targetTable, String timeWindow,
                                      List<String> grainKeys, String grainJson,
                                      String dt, String from, String to, int maxRows) {
        String table = assertSafeTable(targetTable);
        List<String> grains = resolveGrains(grainKeys, grainJson);
        String timeCol = MetricParamBinder.pickTimeCol(grains);
        String where = trinoTimePredicate(timeWindow, timeCol, dt, from, to);
        int n = Math.max(1, Math.min(maxRows, 10000));
        return "SELECT * FROM " + table + " WHERE " + where + " LIMIT " + n;
    }

    public static String assertSafeTable(String targetTable) {
        String t = StrUtil.trim(targetTable);
        if (StrUtil.isBlank(t) || !SAFE_TABLE.matcher(t).matches()) {
            throw new IllegalArgumentException("非法物化表名: " + targetTable);
        }
        return t;
    }

    static String ckTimePredicate(String timeWindow, String timeCol, String dt, String from, String to) {
        String col = StrUtil.blankToDefault(timeCol, "dt");
        if (isRangeWindow(timeWindow)) {
            return col + " BETWEEN toDate('" + dtOr(from) + "') AND toDate('" + dtOr(to) + "')";
        }
        return col + " = toDate('" + dtOr(dt) + "')";
    }

    static String trinoTimePredicate(String timeWindow, String timeCol, String dt, String from, String to) {
        String col = StrUtil.blankToDefault(timeCol, "dt");
        if (isRangeWindow(timeWindow)) {
            return col + " BETWEEN DATE '" + dtOr(from) + "' AND DATE '" + dtOr(to) + "'";
        }
        return col + " = DATE '" + dtOr(dt) + "'";
    }

    private static boolean isRangeWindow(String timeWindow) {
        String w = StrUtil.blankToDefault(timeWindow, "近1天").trim();
        return switch (w) {
            case "近7天", "近30天", "自然周", "自然月", "累计" -> true;
            default -> false;
        };
    }

    private static String dtOr(String v) {
        if (StrUtil.isBlank(v)) {
            throw new IllegalArgumentException("物化改写缺少日期参数");
        }
        return v.trim();
    }

    @SuppressWarnings("unchecked")
    private static List<String> resolveGrains(List<String> grainKeys, String grainJson) {
        if (grainKeys != null && !grainKeys.isEmpty()) {
            return grainKeys;
        }
        if (StrUtil.isBlank(grainJson)) {
            return List.of("dt");
        }
        try {
            Object parsed = JSONUtil.parse(grainJson);
            if (parsed instanceof List<?> list) {
                List<String> out = new ArrayList<>();
                for (Object o : list) {
                    if (o != null) {
                        out.add(String.valueOf(o));
                    }
                }
                return out.isEmpty() ? List.of("dt") : out;
            }
        } catch (Exception ignored) {
        }
        return List.of("dt");
    }

    /** 引擎名归一：clickhouse / iceberg / trino → 查找键 */
    public static String normalizeEngine(String engine) {
        String e = StrUtil.blankToDefault(engine, "").trim().toLowerCase(Locale.ROOT);
        if ("ck".equals(e) || "hot".equals(e)) {
            return "clickhouse";
        }
        if ("iceberg".equals(e) || "ads".equals(e)) {
            return "iceberg";
        }
        return e;
    }
}
