package vip.xiaonuo.lh.modular.metric.support;

import cn.hutool.core.util.StrUtil;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 指标 SQL 参数白名单绑定：只替换 {{dt}}/{{from}}/{{to}} 与 {{dim.x}}。
 */
public final class MetricParamBinder {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z0-9_.]+)\\}\\}");
    private static final Pattern SAFE_DIM = Pattern.compile("^[A-Za-z0-9_\\-.\\u4e00-\\u9fa5]{1,64}$");

    private MetricParamBinder() {
    }

    /**
     * @param sqlTemplate 含占位符的编译 SQL
     * @param params      调用方参数
     * @param timeWindow  指标 time_window（用于缺省日期）
     * @param grainKeys   允许的维度键
     */
    public static BindOut bind(String sqlTemplate, Map<String, Object> params,
                               String timeWindow, List<String> grainKeys) {
        Map<String, Object> p = params == null ? Map.of() : params;
        LocalDate today = LocalDate.now();
        String dt = str(p.get("dt"));
        String from = str(p.get("from"));
        String to = str(p.get("to"));
        DateRange range = defaultRange(timeWindow, today);
        if (StrUtil.isBlank(dt)) {
            dt = range.dt;
        }
        if (StrUtil.isBlank(from)) {
            from = range.from;
        }
        if (StrUtil.isBlank(to)) {
            to = range.to;
        }
        assertDate(dt, "dt");
        assertDate(from, "from");
        assertDate(to, "to");

        @SuppressWarnings("unchecked")
        Map<String, Object> dims = p.get("dims") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();

        List<String> grains = grainKeys == null ? List.of() : grainKeys;
        Matcher m = PLACEHOLDER.matcher(StrUtil.nullToEmpty(sqlTemplate));
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            String repl;
            if ("dt".equals(key)) {
                repl = dt;
            } else if ("from".equals(key)) {
                repl = from;
            } else if ("to".equals(key)) {
                repl = to;
            } else if (key.startsWith("dim.")) {
                String dimKey = key.substring(4);
                if (!grains.isEmpty() && !grains.contains(dimKey)) {
                    throw new IllegalArgumentException("维度不在指标粒度中: " + dimKey);
                }
                Object raw = dims.get(dimKey);
                if (raw == null) {
                    raw = p.get(dimKey);
                }
                List<String> vals = normalizeDimValues(raw);
                if (vals.isEmpty()) {
                    throw new IllegalArgumentException("缺少维度参数: " + dimKey);
                }
                for (String v : vals) {
                    if (!SAFE_DIM.matcher(v).matches()) {
                        throw new IllegalArgumentException("非法维度值: " + dimKey);
                    }
                }
                // 占位符 {{dim.x}} 仅单值；多值由 dimFilterPredicates 生成 IN 字面量
                if (vals.size() > 1) {
                    throw new IllegalArgumentException(
                            "维度 " + dimKey + " 为多值，请使用编译期 IN 谓词（勿在模板写单值占位符）");
                }
                repl = vals.get(0).replace("'", "''");
            } else {
                throw new IllegalArgumentException("不支持的占位符: {{" + key + "}}");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(repl));
        }
        m.appendTail(sb);
        return new BindOut(sb.toString(), dt, from, to);
    }

    public static String timePredicate(String timeWindow, String dtCol) {
        String col = StrUtil.blankToDefault(dtCol, "dt");
        String w = StrUtil.blankToDefault(timeWindow, "近1天").trim();
        return switch (w) {
            case "近7天", "近30天", "自然周", "自然月", "累计" ->
                    col + " BETWEEN DATE '{{from}}' AND DATE '{{to}}'";
            default -> col + " = DATE '{{dt}}'";
        };
    }

    public static DateRange defaultRange(String timeWindow, LocalDate today) {
        String w = StrUtil.blankToDefault(timeWindow, "近1天").trim();
        LocalDate end = today.minusDays(1);
        LocalDate start = switch (w) {
            case "近7天", "自然周" -> end.minusDays(6);
            case "近30天", "自然月" -> end.minusDays(29);
            case "累计" -> end.minusDays(365);
            default -> end;
        };
        return new DateRange(end.format(ISO), start.format(ISO), end.format(ISO));
    }

    private static void assertDate(String v, String name) {
        try {
            LocalDate.parse(v, ISO);
        } catch (Exception e) {
            throw new IllegalArgumentException("非法日期参数 " + name + ": " + v);
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    public record DateRange(String dt, String from, String to) {
    }

    public record BindOut(String sql, String dt, String from, String to) {
    }

    /** 从 grain 中挑时间列 */
    public static String pickTimeCol(List<String> grains) {
        if (grains == null || grains.isEmpty()) {
            return "dt";
        }
        for (String g : grains) {
            String x = g.toLowerCase(Locale.ROOT);
            if ("dt".equals(x) || "stat_date".equals(x) || "biz_date".equals(x)) {
                return g;
            }
        }
        return grains.get(0);
    }

    public static List<String> dimFilterPredicates(Map<String, Object> params, List<String> grainKeys) {
        if (params == null || grainKeys == null || grainKeys.isEmpty()) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> dims = params.get("dims") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();
        List<String> out = new ArrayList<>();
        for (String g : grainKeys) {
            if ("dt".equalsIgnoreCase(g) || "stat_date".equalsIgnoreCase(g) || "biz_date".equalsIgnoreCase(g)) {
                continue;
            }
            Object raw = dims.get(g);
            if (raw == null) {
                raw = params.get(g);
            }
            List<String> vals = normalizeDimValues(raw);
            if (vals.isEmpty()) {
                continue;
            }
            for (String v : vals) {
                if (!SAFE_DIM.matcher(v).matches()) {
                    throw new IllegalArgumentException("非法维度值: " + g);
                }
            }
            if (vals.size() == 1) {
                out.add(g + " = '{{dim." + g + "}}'");
            } else {
                // 已校验白名单，直接写 IN 字面量（避免多占位符）
                StringBuilder in = new StringBuilder();
                for (int i = 0; i < vals.size(); i++) {
                    if (i > 0) {
                        in.append(", ");
                    }
                    in.append("'").append(vals.get(i).replace("'", "''")).append("'");
                }
                out.add(g + " IN (" + in + ")");
            }
        }
        return out;
    }

    /**
     * 维度值规范化：单值字符串 / 数组 / 逗号分隔（当整串不符合 SAFE_DIM 时拆分）。
     */
    public static List<String> normalizeDimValues(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof Collection<?> c) {
            List<String> out = new ArrayList<>();
            for (Object o : c) {
                String s = str(o);
                if (StrUtil.isNotBlank(s)) {
                    out.add(s);
                }
            }
            return out;
        }
        if (raw.getClass().isArray()) {
            int n = java.lang.reflect.Array.getLength(raw);
            List<String> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                String s = str(java.lang.reflect.Array.get(raw, i));
                if (StrUtil.isNotBlank(s)) {
                    out.add(s);
                }
            }
            return out;
        }
        String s = str(raw);
        if (StrUtil.isBlank(s)) {
            return List.of();
        }
        if (SAFE_DIM.matcher(s).matches()) {
            return List.of(s);
        }
        if (s.contains(",")) {
            List<String> out = new ArrayList<>();
            for (String part : s.split(",")) {
                String t = part.trim();
                if (StrUtil.isNotBlank(t)) {
                    out.add(t);
                }
            }
            return out;
        }
        return List.of(s);
    }

    /** 供 Guest RLS 等场景生成已转义的 SQL 谓词片段（不含 WHERE） */
    public static String dimEqualsOrInClause(String column, Object raw) {
        if (StrUtil.isBlank(column)) {
            throw new IllegalArgumentException("维度列为空");
        }
        List<String> vals = normalizeDimValues(raw);
        if (vals.isEmpty()) {
            return "";
        }
        for (String v : vals) {
            if (!SAFE_DIM.matcher(v).matches()) {
                throw new IllegalArgumentException("非法维度值: " + column);
            }
        }
        String col = column.trim();
        if (vals.size() == 1) {
            return col + " = '" + vals.get(0).replace("'", "''") + "'";
        }
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < vals.size(); i++) {
            if (i > 0) {
                in.append(", ");
            }
            in.append("'").append(vals.get(i).replace("'", "''")).append("'");
        }
        return col + " IN (" + in + ")";
    }
}
