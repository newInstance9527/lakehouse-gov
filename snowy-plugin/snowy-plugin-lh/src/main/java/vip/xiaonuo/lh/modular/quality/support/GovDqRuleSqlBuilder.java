package vip.xiaonuo.lh.modular.quality.support;

import cn.hutool.core.util.StrUtil;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 质量规则 → 探针 SQL（返回一行 {@code total_cnt}/{@code fail_cnt}）。
 * <p>不负责执行；表名/字段须已做标识符校验。</p>
 */
public final class GovDqRuleSqlBuilder {

    private static final Pattern SAFE_IDENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Pattern SAFE_QUALIFIED = Pattern.compile(
            "^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*){0,2}$");

    private GovDqRuleSqlBuilder() {
    }

    public static final class Plan {
        public final String sql;
        public final String mode;
        public final String hint;

        public Plan(String sql, String mode, String hint) {
            this.sql = sql;
            this.mode = mode;
            this.hint = hint;
        }
    }

    /**
     * @param qualifiedTable 已引用好的表名，如 {@code "iceberg"."ods_trade"."s_order"} 或 JDBC {@code `t`}
     * @param dialect        trino / mysql / postgres（影响少量语法；默认 trino）
     */
    public static Plan build(String ruleType, String ruleCode, String scope, String fieldName,
                             String exprText, String qualifiedTable, String dialect) {
        if (StrUtil.isBlank(qualifiedTable)) {
            throw new IllegalArgumentException("表名为空，无法探数");
        }
        String expr = StrUtil.nullToEmpty(exprText).trim();
        String field = StrUtil.nullToEmpty(fieldName).trim();
        if (StrUtil.isNotBlank(field) && !SAFE_IDENT.matcher(field).matches()) {
            throw new IllegalArgumentException("非法字段名: " + field);
        }
        if (StrUtil.isNotBlank(expr)) {
            expr = expr.replace("{field}", quoteBare(field, dialect));
            // 预设里常用 FROM T 占位
            expr = replaceFromT(expr, qualifiedTable);
        }

        String typeKey = normalizeType(ruleType, ruleCode);
        if (StrUtil.isBlank(expr)) {
            return defaultPlan(typeKey, field, qualifiedTable, dialect, scope);
        }
        String upper = expr.toUpperCase(Locale.ROOT);
        if (upper.startsWith("SELECT")) {
            return selectPlan(expr, qualifiedTable, dialect);
        }
        if (looksLikeAggregateAssert(expr)) {
            String sql = "SELECT COUNT(1) AS total_cnt, "
                    + "CASE WHEN (" + expr + ") THEN 0 ELSE 1 END AS fail_cnt FROM " + qualifiedTable;
            return new Plan(sql, "agg_assert", "聚合断言：" + abbreviate(expr));
        }
        // 行级谓词：不满足则计失败行
        String sql = "SELECT COUNT(1) AS total_cnt, "
                + "COALESCE(SUM(CASE WHEN NOT (" + expr + ") THEN 1 ELSE 0 END), 0) AS fail_cnt FROM "
                + qualifiedTable;
        return new Plan(sql, "row_predicate", "行级谓词：" + abbreviate(expr));
    }

    /**
     * 枚举/码值：字段不在允许集或为 NULL 计失败。
     */
    public static Plan buildEnum(String fieldName, List<String> allowedCodes,
                                 String qualifiedTable, String dialect) {
        if (StrUtil.isBlank(qualifiedTable)) {
            throw new IllegalArgumentException("表名为空，无法探数");
        }
        String field = StrUtil.nullToEmpty(fieldName).trim();
        if (!isSafeIdent(field)) {
            throw new IllegalArgumentException("非法字段名: " + field);
        }
        if (allowedCodes == null || allowedCodes.isEmpty()) {
            throw new IllegalArgumentException("码值集无枚举项，无法生成探针");
        }
        String f = quoteBare(field, dialect);
        StringBuilder inList = new StringBuilder();
        int n = 0;
        for (String code : allowedCodes) {
            if (StrUtil.isBlank(code)) {
                continue;
            }
            if (n > 0) {
                inList.append(',');
            }
            inList.append(sqlStringLiteral(code.trim(), dialect));
            n++;
            if (n >= 500) {
                break;
            }
        }
        if (n == 0) {
            throw new IllegalArgumentException("码值集无有效枚举项");
        }
        String sql = "SELECT COUNT(1) AS total_cnt, "
                + "COALESCE(SUM(CASE WHEN " + f + " IS NULL OR " + f + " NOT IN (" + inList + ") THEN 1 ELSE 0 END), 0) AS fail_cnt FROM "
                + qualifiedTable;
        return new Plan(sql, "enum_std", "码值合规 IN(" + n + "项) · " + field);
    }

    /** Trino 三元标识：catalog.schema.table → 带引号 */
    public static String qualifyTrino(String catalog, String schema, String table) {
        return quoteBare(catalog, "trino") + "." + quoteBare(schema, "trino") + "." + quoteBare(table, "trino");
    }

    public static void assertSafeTableToken(String raw) {
        if (StrUtil.isBlank(raw) || !SAFE_QUALIFIED.matcher(raw.trim()).matches()) {
            throw new IllegalArgumentException("非法表名: " + raw);
        }
    }

    public static boolean isSafeIdent(String raw) {
        return StrUtil.isNotBlank(raw) && SAFE_IDENT.matcher(raw.trim()).matches();
    }

    private static Plan defaultPlan(String typeKey, String field, String table, String dialect, String scope) {
        if ("unique".equals(typeKey)) {
            if (StrUtil.isBlank(field)) {
                throw new IllegalArgumentException("主键唯一规则需要字段名");
            }
            String f = quoteBare(field, dialect);
            String sql = "SELECT COUNT(1) AS total_cnt, "
                    + "(COUNT(1) - COUNT(DISTINCT " + f + ")) AS fail_cnt FROM " + table;
            return new Plan(sql, "unique", "默认主键唯一：" + field);
        }
        if ("not_null".equals(typeKey)) {
            if (StrUtil.isBlank(field)) {
                throw new IllegalArgumentException("非空规则需要字段名");
            }
            String f = quoteBare(field, dialect);
            String sql = "SELECT COUNT(1) AS total_cnt, "
                    + "COALESCE(SUM(CASE WHEN " + f + " IS NULL THEN 1 ELSE 0 END), 0) AS fail_cnt FROM " + table;
            return new Plan(sql, "not_null", "默认非空：" + field);
        }
        if ("enum".equals(typeKey)) {
            throw new IllegalArgumentException("枚举规则须绑定 gov_std_code（stdCodeSetId）或配置 IN 表达式");
        }
        if ("custom".equals(typeKey) || "row_count".equals(typeKey) || "range".equals(typeKey)) {
            throw new IllegalArgumentException("规则类型「" + typeKey + "」须配置表达式 exprText");
        }
        throw new IllegalArgumentException("无法为规则类型生成探针 SQL: " + typeKey
                + (StrUtil.isBlank(scope) ? "" : ("/" + scope)));
    }

    private static Plan selectPlan(String selectSql, String qualifiedTable, String dialect) {
        String upper = selectSql.toUpperCase(Locale.ROOT);
        if (upper.contains("TOTAL_CNT") && upper.contains("FAIL_CNT")) {
            return new Plan(selectSql, "custom_metrics", "自定义 total_cnt/fail_cnt");
        }
        if (upper.contains(" HAVING ")) {
            // 查重类：外层统计重复组数
            String sql = "SELECT (SELECT COUNT(1) FROM " + qualifiedTable + ") AS total_cnt, "
                    + "(SELECT COUNT(1) FROM (" + selectSql + ") __dq_dup) AS fail_cnt";
            return new Plan(sql, "select_dup", "HAVING 查重包装");
        }
        // 其它 SELECT：失败行 = 结果集行数；总数另计
        String sql = "SELECT (SELECT COUNT(1) FROM " + qualifiedTable + ") AS total_cnt, "
                + "(SELECT COUNT(1) FROM (" + selectSql + ") __dq_bad) AS fail_cnt";
        return new Plan(sql, "select_bad_rows", "自定义 SELECT 失败行包装");
    }

    private static boolean looksLikeAggregateAssert(String expr) {
        String u = expr.toUpperCase(Locale.ROOT);
        return u.contains("SUM(") || u.contains("COUNT(") || u.contains("AVG(")
                || u.contains("MIN(") || u.contains("MAX(");
    }

    private static String normalizeType(String ruleType, String ruleCode) {
        String raw = StrUtil.blankToDefault(ruleType, ruleCode).trim().toLowerCase(Locale.ROOT);
        if (raw.contains("主键") || raw.contains("unique") || raw.contains("pk_") || "pk_unique".equals(raw)) {
            return "unique";
        }
        if (raw.contains("非空") || raw.contains("null") || raw.contains("not_null") || "null_check".equals(raw)) {
            return "not_null";
        }
        if (raw.contains("枚举") || raw.contains("enum") || raw.contains("码值")) {
            return "enum";
        }
        if (raw.contains("范围") || raw.contains("range")) {
            return "range";
        }
        if (raw.contains("行数") || raw.contains("row_count") || raw.contains("阈值")) {
            return "row_count";
        }
        if (raw.contains("自定义") || raw.contains("custom") || raw.contains("sql")) {
            return "custom";
        }
        return raw;
    }

    private static String replaceFromT(String expr, String qualifiedTable) {
        // FROM T / from t / FROM T) 等
        Matcher m = Pattern.compile("(?i)\\bFROM\\s+T\\b").matcher(expr);
        return m.replaceAll("FROM " + Matcher.quoteReplacement(qualifiedTable));
    }

    private static String quoteBare(String ident, String dialect) {
        if (StrUtil.isBlank(ident)) {
            return ident;
        }
        String d = StrUtil.blankToDefault(dialect, "trino").toLowerCase(Locale.ROOT);
        if (d.contains("mysql")) {
            return "`" + ident.replace("`", "``") + "`";
        }
        if (d.contains("postgres") || d.contains("pg") || d.contains("oracle")) {
            return "\"" + ident.replace("\"", "\"\"") + "\"";
        }
        if (d.contains("sqlserver")) {
            return "[" + ident.replace("]", "]]") + "]";
        }
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    private static String sqlStringLiteral(String raw, String dialect) {
        String d = StrUtil.blankToDefault(dialect, "trino").toLowerCase(Locale.ROOT);
        String s = StrUtil.nullToEmpty(raw).replace("'", "''");
        if (d.contains("mysql")) {
            return "'" + s.replace("\\", "\\\\") + "'";
        }
        return "'" + s + "'";
    }

    private static String abbreviate(String s) {
        return StrUtil.maxLength(StrUtil.nullToEmpty(s).replace('\n', ' '), 120);
    }
}
