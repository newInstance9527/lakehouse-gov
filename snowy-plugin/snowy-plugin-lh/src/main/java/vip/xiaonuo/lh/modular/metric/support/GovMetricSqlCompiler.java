package vip.xiaonuo.lh.modular.metric.support;

import cn.hutool.core.util.StrUtil;
import vip.xiaonuo.lh.modular.metric.entity.GovMetric;
import vip.xiaonuo.lh.modular.metric.entity.GovMetricVer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Trino SQL 编译：原子 / 衍生 / 复合（CTE）；时间窗与维度占位符由 {@link MetricParamBinder} 填充。
 */
public final class GovMetricSqlCompiler {

    private static final Pattern REF = Pattern.compile("(?i)\\b([ACM]-\\d{4})\\b");
    private static final String DEFAULT_CATALOG = "iceberg";

    private GovMetricSqlCompiler() {
    }

    public static CompileOut compile(GovMetric head, GovMetricVer ver, String dialect,
                                     Function<String, MetricNode> resolveDep) {
        return compile(head, ver, dialect, resolveDep, Map.of());
    }

    /**
     * @param params 用于附加维度过滤谓词模板（执行前再 bind）
     */
    public static CompileOut compile(GovMetric head, GovMetricVer ver, String dialect,
                                     Function<String, MetricNode> resolveDep,
                                     Map<String, Object> params) {
        String d = StrUtil.blankToDefault(dialect, "trino").toLowerCase(Locale.ROOT);
        if (!"trino".equals(d) && !"clickhouse".equals(d)) {
            d = "trino";
        }
        // M1：clickhouse 暂用 trino 方言文本，执行层会回退 Trino
        String kind = head.getKind();
        List<String> closure = new ArrayList<>();
        List<String> assets = new ArrayList<>();
        List<String> grains = parseJsonArray(ver.getGrainJson());
        String sql;
        if ("原子".equals(kind)) {
            sql = compileAtomSelect(ver, assets, null, null, params);
            closure.add(head.getMetricCode());
        } else if ("衍生".equals(kind)) {
            MetricNode atom = resolveDep.apply(ver.getAtomRef());
            if (atom == null || atom.ver() == null) {
                throw new IllegalArgumentException("衍生指标依赖原子不存在: " + ver.getAtomRef());
            }
            if (!"原子".equals(atom.head().getKind())) {
                throw new IllegalArgumentException("衍生只能依赖原子指标: " + ver.getAtomRef());
            }
            requireActiveOrTrial(atom.head());
            closure.add(head.getMetricCode());
            closure.add(atom.code());
            sql = compileDerivedSelect(ver, atom.ver(), assets, params);
        } else if ("复合".equals(kind)) {
            GovMetricFormulaParser.Result parsed = GovMetricFormulaParser.parse(ver.getFormula());
            if (!parsed.ok()) {
                throw new IllegalArgumentException(parsed.error());
            }
            closure.add(head.getMetricCode());
            sql = compileComposite(head, ver, parsed, resolveDep, closure, assets, params);
        } else {
            throw new IllegalArgumentException("未知指标类型: " + kind);
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("dialect", "trino");
        meta.put("metricCode", head.getMetricCode());
        meta.put("ver", ver.getVer());
        meta.put("depClosure", closure);
        meta.put("bindAssets", assets);
        meta.put("grainKeys", grains);
        meta.put("timeWindow", ver.getTimeWindow());
        meta.put("sqlText", sql);
        return new CompileOut("trino", sql, closure, assets, grains, ver.getTimeWindow(), meta);
    }

    private static void requireActiveOrTrial(GovMetric head) {
        // 草稿试跑允许依赖非 active；正式 query 由调用方保证 head active，依赖建议 active
        String st = head.getStatus();
        if ("deprecated".equals(st)) {
            throw new IllegalArgumentException("依赖指标已废弃: " + head.getMetricCode());
        }
    }

    private static String compileComposite(GovMetric head, GovMetricVer ver,
                                           GovMetricFormulaParser.Result parsed,
                                           Function<String, MetricNode> resolveDep,
                                           List<String> closure, List<String> assets,
                                           Map<String, Object> params) {
        // 收集叶子 grain 交集
        List<List<String>> grainSets = new ArrayList<>();
        Map<String, String> cteSql = new LinkedHashMap<>();
        for (String ref : parsed.refs()) {
            if (!closure.contains(ref)) {
                closure.add(ref);
            }
            MetricNode node = resolveDep.apply(ref);
            if (node == null || node.ver() == null) {
                throw new IllegalArgumentException("复合依赖不存在: " + ref);
            }
            requireActiveOrTrial(node.head());
            List<String> g = parseJsonArray(node.ver().getGrainJson());
            if ("原子".equals(node.head().getKind())) {
                g = List.of(); // 原子无粒度 → 标量，复合时按全表一行
            }
            grainSets.add(g);
            String leaf;
            if ("原子".equals(node.head().getKind())) {
                leaf = compileAtomSelect(node.ver(), assets, null, null, params);
            } else if ("衍生".equals(node.head().getKind())) {
                MetricNode atom = resolveDep.apply(node.ver().getAtomRef());
                if (atom == null || atom.ver() == null) {
                    throw new IllegalArgumentException("衍生依赖原子缺失: " + node.ver().getAtomRef());
                }
                if (!closure.contains(atom.code())) {
                    closure.add(atom.code());
                }
                leaf = compileDerivedSelect(node.ver(), atom.ver(), assets, params);
            } else {
                throw new IllegalArgumentException("复合暂不支持嵌套复合依赖: " + ref);
            }
            // CTE 需要统一别名 metric_value；有粒度时保留 grain 列
            cteSql.put(ref, leaf);
        }
        List<String> interGrain = intersectGrains(grainSets);
        StringBuilder sb = new StringBuilder();
        sb.append("WITH\n");
        int i = 0;
        List<String> cteNames = new ArrayList<>();
        for (Map.Entry<String, String> e : cteSql.entrySet()) {
            if (i > 0) {
                sb.append(",\n");
            }
            String cte = cteAlias(e.getKey());
            cteNames.add(cte);
            sb.append("  ").append(cte).append(" AS (\n");
            sb.append(indent(e.getValue(), 4));
            sb.append("\n  )");
            i++;
        }
        sb.append("\n");
        // SELECT expr
        String exprSql = formulaToSql(parsed.expr(), parsed.refs());
        if (interGrain.isEmpty()) {
            // 标量：各 CTE 一行
            sb.append("SELECT ").append(exprSql).append(" AS metric_value\n");
            sb.append("FROM ").append(cteNames.get(0));
            for (int j = 1; j < cteNames.size(); j++) {
                sb.append("\nCROSS JOIN ").append(cteNames.get(j));
            }
        } else {
            sb.append("SELECT ");
            for (String g : interGrain) {
                sb.append("a0.").append(quoteIdent(g)).append(", ");
            }
            sb.append(exprSql).append(" AS metric_value\n");
            sb.append("FROM ").append(cteNames.get(0)).append(" a0");
            for (int j = 1; j < cteNames.size(); j++) {
                sb.append("\nJOIN ").append(cteNames.get(j)).append(" a").append(j).append(" ON ");
                List<String> ons = new ArrayList<>();
                for (String g : interGrain) {
                    ons.add("a0." + quoteIdent(g) + " = a" + j + "." + quoteIdent(g));
                }
                sb.append(String.join(" AND ", ons));
            }
        }
        if (StrUtil.isNotBlank(parsed.filter())) {
            sb.append("\n-- extra filter reserved: ").append(parsed.filter());
        }
        return sb.toString();
    }

    private static List<String> intersectGrains(List<List<String>> sets) {
        if (sets.isEmpty()) {
            return List.of();
        }
        // 全是空 → 标量
        if (sets.stream().allMatch(List::isEmpty)) {
            return List.of();
        }
        // 有的有粒度有的没有 → 失败
        if (sets.stream().anyMatch(List::isEmpty)) {
            throw new IllegalArgumentException("复合依赖粒度不一致：部分为全表汇总、部分带 GROUP BY");
        }
        Set<String> inter = new LinkedHashSet<>(sets.get(0));
        for (int i = 1; i < sets.size(); i++) {
            inter.retainAll(new LinkedHashSet<>(sets.get(i)));
        }
        if (inter.isEmpty()) {
            throw new IllegalArgumentException("复合依赖无公共统计粒度，无法对齐");
        }
        return new ArrayList<>(inter);
    }

    private static String formulaToSql(String expr, List<String> refs) {
        // M-0001 / A-0012 → a0.metric_value / a1.metric_value（按 refs 顺序）
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < refs.size(); i++) {
            map.put(refs.get(i).toUpperCase(Locale.ROOT), "a" + i + ".metric_value");
        }
        Matcher m = REF.matcher(expr);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String id = m.group(1).toUpperCase(Locale.ROOT);
            String repl = map.get(id);
            if (repl == null) {
                throw new IllegalArgumentException("公式引用未解析: " + id);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(repl));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String cteAlias(String code) {
        return "m_" + code.replace('-', '_').toLowerCase(Locale.ROOT);
    }

    private static String compileDerivedSelect(GovMetricVer derived, GovMetricVer atom,
                                               List<String> assets, Map<String, Object> params) {
        List<String> grains = parseJsonArray(derived.getGrainJson());
        List<String> where = new ArrayList<>();
        where.addAll(parseJsonArray(atom.getQualifierJson()));
        where.addAll(parseJsonArray(derived.getQualifierJson()));
        String timeCol = MetricParamBinder.pickTimeCol(grains);
        where.add(MetricParamBinder.timePredicate(derived.getTimeWindow(), timeCol));
        where.addAll(MetricParamBinder.dimFilterPredicates(params, grains));
        return compileAtomSelect(atom, assets, grains, where, params);
    }

    private static String compileAtomSelect(GovMetricVer atom, List<String> assets,
                                            List<String> grains, List<String> extraWhere,
                                            Map<String, Object> params) {
        String table = qualifyTable(StrUtil.blankToDefault(atom.getBindTable(), "unknown_table"));
        String field = StrUtil.blankToDefault(atom.getBindField(), "unknown_col");
        String agg = StrUtil.blankToDefault(atom.getAgg(), "COUNT").toUpperCase(Locale.ROOT);
        assets.add(table);
        String measure = measureExpr(agg, field);
        List<String> where = new ArrayList<>();
        if (extraWhere != null) {
            where.addAll(extraWhere);
        } else {
            where.addAll(parseJsonArray(atom.getQualifierJson()));
            // 纯原子试跑：无时间窗时不加强制日期，执行层加 LIMIT
        }
        List<String> g = grains == null ? List.of() : grains;
        StringBuilder sb = new StringBuilder();
        if (g.isEmpty()) {
            sb.append("SELECT ").append(measure).append(" AS metric_value\n");
            sb.append("FROM ").append(table);
        } else {
            sb.append("SELECT ");
            for (String col : g) {
                sb.append(quoteIdent(col)).append(", ");
            }
            sb.append(measure).append(" AS metric_value\n");
            sb.append("FROM ").append(table);
        }
        if (!where.isEmpty()) {
            sb.append("\nWHERE ").append(String.join(" AND ", where));
        }
        if (!g.isEmpty()) {
            sb.append("\nGROUP BY ");
            sb.append(String.join(", ", g.stream().map(GovMetricSqlCompiler::quoteIdent).toList()));
        }
        return sb.toString();
    }

    private static String measureExpr(String agg, String field) {
        String f = quoteIdent(field);
        return switch (agg) {
            case "COUNT DISTINCT", "COUNT_DISTINCT" -> "count(DISTINCT " + f + ")";
            case "COUNT" -> "count(" + f + ")";
            case "SUM" -> "sum(" + f + ")";
            case "AVG" -> "avg(" + f + ")";
            case "MAX" -> "max(" + f + ")";
            case "MIN" -> "min(" + f + ")";
            default -> agg.toLowerCase(Locale.ROOT) + "(" + f + ")";
        };
    }

    /** schema.table → iceberg.schema.table；已三节则原样 */
    public static String qualifyTable(String table) {
        String t = StrUtil.trim(table);
        if (StrUtil.isBlank(t) || "unknown_table".equals(t)) {
            return t;
        }
        long dots = t.chars().filter(c -> c == '.').count();
        if (dots >= 2) {
            return t;
        }
        if (dots == 1) {
            return DEFAULT_CATALOG + "." + t;
        }
        return DEFAULT_CATALOG + ".default." + t;
    }

    private static String indent(String sql, int spaces) {
        String pad = " ".repeat(spaces);
        String[] lines = sql.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(pad).append(lines[i]);
        }
        return sb.toString();
    }

    private static String quoteIdent(String ident) {
        if (StrUtil.isBlank(ident)) {
            return "col";
        }
        if (ident.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return ident;
        }
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    public static List<String> parseJsonArray(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        String s = json.trim();
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1).trim();
            if (s.isEmpty()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (String part : s.split(",")) {
                String p = part.trim();
                if (p.startsWith("\"") && p.endsWith("\"") && p.length() >= 2) {
                    p = p.substring(1, p.length() - 1);
                }
                p = p.replace("''", "'");
                if (StrUtil.isNotBlank(p)) {
                    out.add(p);
                }
            }
            return out;
        }
        return List.of(s);
    }

    public record MetricNode(String code, GovMetric head, GovMetricVer ver) {
    }

    public record CompileOut(String dialect, String sqlText, List<String> depClosure,
                             List<String> bindAssets, List<String> grainKeys, String timeWindow,
                             Map<String, Object> meta) {
    }
}
