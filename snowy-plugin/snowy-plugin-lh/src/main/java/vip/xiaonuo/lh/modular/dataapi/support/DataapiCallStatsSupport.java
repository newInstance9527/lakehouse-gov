package vip.xiaonuo.lh.modular.dataapi.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 归一化 SQLREST overview counter / trend / topPath，供 KPI 与调用大盘复用。
 */
public final class DataapiCallStatsSupport {

    private DataapiCallStatsSupport() {
    }

    public static Map<String, Object> assemble(int days,
                                               Map<String, Object> counterRaw,
                                               Map<String, Object> trendRaw,
                                               Map<String, Object> topRaw) {
        int d = Math.max(1, days);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", d);
        out.put("source", "sqlrest");

        Map<String, Object> counter = unwrapMap(counterRaw);
        out.put("counter", counter);
        out.put("counterOk", Boolean.TRUE.equals(counterRaw != null ? counterRaw.get("ok") : null)
                || !counter.isEmpty());

        List<Map<String, Object>> trend = normalizeTrend(unwrapList(trendRaw));
        out.put("trend", trend);

        List<Map<String, Object>> topPath = normalizeTop(unwrapList(topRaw));
        out.put("topPath", topPath);

        long calls24h = 0L;
        Double avgLatency = null;
        if (!trend.isEmpty()) {
            Map<String, Object> last = trend.get(trend.size() - 1);
            calls24h = toLong(last.get("calls"));
            Object lat = last.get("latencyMs");
            if (lat != null) {
                avgLatency = toDouble(lat);
            }
        } else if (!topPath.isEmpty()) {
            for (Map<String, Object> t : topPath) {
                calls24h += toLong(t.get("calls"));
            }
        }
        out.put("calls24h", calls24h > 0 ? calls24h : null);
        out.put("avgLatencyMs", avgLatency);
        return out;
    }

    public static void enrichOverview(Map<String, Object> overview, Map<String, Object> stats) {
        if (overview == null || stats == null) {
            return;
        }
        if (stats.get("calls24h") != null) {
            overview.put("calls24h", stats.get("calls24h"));
        }
        if (stats.get("avgLatencyMs") != null) {
            overview.put("avgLatencyMs", stats.get("avgLatencyMs"));
        }
        overview.put("callsNote", stats.get("note"));
        overview.put("callTrend", stats.get("trend"));
        overview.put("callTopPath", stats.get("topPath"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> unwrapMap(Map<String, Object> raw) {
        if (raw == null) {
            return Map.of();
        }
        Object data = raw.get("data");
        if (data instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        if (Boolean.TRUE.equals(raw.get("ok")) && raw.size() > 1) {
            Map<String, Object> out = new LinkedHashMap<>(raw);
            out.remove("ok");
            out.remove("message");
            return out;
        }
        return Map.of();
    }

    private static List<?> unwrapList(Map<String, Object> raw) {
        if (raw == null) {
            return List.of();
        }
        Object data = raw.get("data");
        if (data instanceof List<?> list) {
            return list;
        }
        if (data instanceof JSONArray arr) {
            return arr;
        }
        if (data instanceof String s && StrUtil.isNotBlank(s) && s.trim().startsWith("[")) {
            return JSONUtil.parseArray(s);
        }
        return List.of();
    }

    private static List<Map<String, Object>> normalizeTrend(List<?> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (Object row : rows) {
            JSONObject o = JSONUtil.parseObj(row);
            Map<String, Object> m = new LinkedHashMap<>();
            String day = firstStr(o, "day", "date", "dt", "time", "label");
            long calls = firstLong(o, "calls", "total", "count", "num", "value", "requestCount");
            Double latency = firstDouble(o, "latencyMs", "avgLatency", "avgCost", "avgRt", "rt");
            m.put("day", StrUtil.blankToDefault(day, "—"));
            m.put("calls", calls);
            if (latency != null) {
                m.put("latencyMs", latency);
            }
            out.add(m);
        }
        return out;
    }

    private static List<Map<String, Object>> normalizeTop(List<?> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (Object row : rows) {
            JSONObject o = JSONUtil.parseObj(row);
            Map<String, Object> m = new LinkedHashMap<>();
            String path = firstStr(o, "path", "name", "url", "apiPath");
            long calls = firstLong(o, "calls", "total", "count", "num", "value", "requestCount");
            m.put("path", StrUtil.blankToDefault(path, "—"));
            m.put("calls", calls);
            Object latency = firstDouble(o, "latencyMs", "avgLatency", "avgCost", "avgRt", "rt");
            if (latency != null) {
                m.put("latencyMs", latency);
            }
            out.add(m);
        }
        return out;
    }

    private static String firstStr(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.getStr(k);
            if (StrUtil.isNotBlank(v)) {
                return v;
            }
        }
        return null;
    }

    private static long firstLong(JSONObject o, String... keys) {
        for (String k : keys) {
            Object v = o.get(k);
            if (v != null) {
                return toLong(v);
            }
        }
        return 0L;
    }

    private static Double firstDouble(JSONObject o, String... keys) {
        for (String k : keys) {
            Object v = o.get(k);
            if (v != null) {
                return toDouble(v);
            }
        }
        return null;
    }

    private static long toLong(Object v) {
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).replace(",", "").trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    private static Double toDouble(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v).replace(",", "").trim());
        } catch (Exception e) {
            return null;
        }
    }
}
