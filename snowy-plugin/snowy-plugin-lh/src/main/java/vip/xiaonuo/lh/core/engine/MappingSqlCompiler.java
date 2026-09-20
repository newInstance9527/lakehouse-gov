package vip.xiaonuo.lh.core.engine;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 将 mapping 节点 mapList/fieldMaps 编译为 SELECT 投影 SQL。
 */
public final class MappingSqlCompiler {

    private MappingSqlCompiler() {
    }

    public static String compile(JSONObject conf, String upstreamTable) {
        if (conf == null) {
            return null;
        }
        JSONArray maps = conf.getJSONArray("mapList");
        if (maps == null || maps.isEmpty()) {
            maps = conf.getJSONArray("fieldMaps");
        }
        if (maps == null || maps.isEmpty()) {
            return null;
        }
        String from = firstNonBlank(
                upstreamTable,
                conf.getStr("_lhUpstreamTable"),
                conf.getStr("table"),
                conf.getStr("src"));
        if (StrUtil.isBlank(from)) {
            from = "(SELECT 1 AS _lh_stub) _lh_stub";
        }
        List<String> projections = new ArrayList<>();
        for (int i = 0; i < maps.size(); i++) {
            JSONObject m = maps.getJSONObject(i);
            if (m == null || m.getBool("skip", false)) {
                continue;
            }
            String src = StrUtil.trim(firstNonBlank(m.getStr("src"), m.getStr("source")));
            String dst = StrUtil.trim(firstNonBlank(m.getStr("dst"), m.getStr("target"), src));
            if (StrUtil.isBlank(dst)) {
                continue;
            }
            String expr = firstNonBlank(m.getStr("expr"), m.getStr("transform"));
            String sqlExpr;
            if (StrUtil.isBlank(expr) || "直接映射".equals(expr) || "identity".equalsIgnoreCase(expr)
                    || "CASE".equalsIgnoreCase(expr)) {
                sqlExpr = StrUtil.isBlank(src) ? "NULL" : quoteIdent(src);
            } else if (looksLikeSqlExpr(expr)) {
                sqlExpr = expr;
            } else {
                sqlExpr = StrUtil.isBlank(src) ? "NULL" : quoteIdent(src);
            }
            String cast = StrUtil.trim(m.getStr("type"));
            if (StrUtil.isNotBlank(cast) && !"STRING".equalsIgnoreCase(cast)) {
                sqlExpr = "CAST(" + sqlExpr + " AS " + cast.replaceAll("[^A-Za-z0-9_(),\\s]", "") + ")";
            }
            projections.add(sqlExpr + " AS " + quoteIdent(dst));
        }
        if (projections.isEmpty()) {
            return null;
        }
        return "-- mapping compiled from mapList\nSELECT\n  "
                + String.join(",\n  ", projections)
                + "\nFROM " + from;
    }

    private static boolean looksLikeSqlExpr(String expr) {
        String e = expr.toLowerCase();
        return e.contains("(") || e.contains("/") || e.contains("+") || e.contains("case")
                || e.contains("when") || e.contains("::");
    }

    private static String quoteIdent(String s) {
        return "`" + s.replace("`", "") + "`";
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (StrUtil.isNotBlank(v)) {
                return v.trim();
            }
        }
        return null;
    }
}
