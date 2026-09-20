package vip.xiaonuo.lh.core.engine;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 将 clean 节点 {@code fieldRules} 编译为可在 Flink/Spark SQL 执行的 SELECT。
 * <p>无 fieldRules 时退回 {@code SELECT * FROM upstream}；无上游表时返回注释 + 探测 SQL。</p>
 */
public final class CleanSqlCompiler {

    private CleanSqlCompiler() {
    }

    public static String compile(JSONObject conf, String upstreamTable) {
        if (conf == null) {
            conf = new JSONObject();
        }
        String from = firstNonBlank(
                upstreamTable,
                conf.getStr("_lhUpstreamTable"),
                conf.getStr("table"),
                conf.getStr("src"),
                conf.getStr("objectName"));
        JSONArray rules = conf.getJSONArray("fieldRules");
        if (rules == null || rules.isEmpty()) {
            // 兼容旧 maskCols
            JSONObject legacy = conf.getJSONObject("rules");
            if (legacy != null && legacy.getJSONArray("maskCols") != null
                    && !legacy.getJSONArray("maskCols").isEmpty()) {
                rules = new JSONArray();
                JSONArray cols = legacy.getJSONArray("maskCols");
                String maskRule = StrUtil.blankToDefault(legacy.getStr("maskRule"), "mask_middle");
                for (int i = 0; i < cols.size(); i++) {
                    JSONObject r = new JSONObject();
                    r.set("field", cols.getStr(i));
                    JSONArray maskOps = new JSONArray();
                    maskOps.add("mask");
                    r.set("ops", maskOps);
                    r.set("maskRule", maskRule);
                    rules.add(r);
                }
            }
        }

        if (StrUtil.isBlank(from)) {
            if (rules == null || rules.isEmpty()) {
                return "-- clean: 无上游表且无 fieldRules\nSELECT 1 AS _lh_clean_probe";
            }
            from = "(SELECT 1 AS _lh_stub) _lh_stub";
        }

        List<String> projections = new ArrayList<>();
        List<String> outNames = new ArrayList<>();
        java.util.LinkedHashSet<String> ruled = new java.util.LinkedHashSet<>();
        if (rules != null) {
            for (int i = 0; i < rules.size(); i++) {
                JSONObject r = rules.getJSONObject(i);
                if (r == null) {
                    continue;
                }
                String field = StrUtil.trim(r.getStr("field"));
                if (StrUtil.isBlank(field)) {
                    continue;
                }
                ruled.add(field);
                outNames.add(field);
                projections.add(compileField(field, r) + " AS " + quoteIdent(field));
            }
        }
        // JDBC 落全表：fieldRules 未覆盖的列原样透传，避免 INSERT 列数不足
        if (!projections.isEmpty() && !projections.get(0).equals("*")) {
            for (String extra : passthroughColumns(conf, ruled)) {
                outNames.add(extra);
                projections.add(quoteIdent(extra));
            }
        }
        if (projections.isEmpty()) {
            projections.add("*");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("-- clean compiled from fieldRules\n");
        sb.append("SELECT\n  ");
        sb.append(String.join(",\n  ", projections));
        sb.append("\nFROM ").append(from);

        boolean hasFieldRules = rules != null && !rules.isEmpty();
        boolean dedup = conf.containsKey("dedup")
                ? conf.getBool("dedup", false)
                : false;
        JSONArray keys = conf.getJSONArray("dedupKeys");
        // 仅无 fieldRules 的旧图才回退 conf.rules.dedup（避免顶层 dedup=false 仍被 legacy 打开）
        if (!hasFieldRules && conf.getJSONObject("rules") != null) {
            if (!conf.containsKey("dedup")) {
                dedup = conf.getJSONObject("rules").getBool("dedup", false);
            }
            if (keys == null || keys.isEmpty()) {
                keys = conf.getJSONObject("rules").getJSONArray("dedupKeys");
            }
        }
        if (dedup && keys != null && !keys.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < keys.size(); i++) {
                String k = keys.getStr(i);
                if (StrUtil.isNotBlank(k)) {
                    parts.add(quoteIdent(k.trim()));
                }
            }
            if (!parts.isEmpty()) {
                String keep = StrUtil.blankToDefault(conf.getStr("dedupKeep"), "latest");
                String order = "first".equalsIgnoreCase(keep) ? "ASC" : "DESC";
                String inner = sb.toString();
                sb = new StringBuilder();
                sb.append("-- clean + dedup(").append(String.join(",", parts)).append(")\n");
                // 不用 SELECT * EXCEPT：多数 Spark 3.x 不支持该语法
                if (!outNames.isEmpty()) {
                    List<String> quoted = new ArrayList<>();
                    for (String n : outNames) {
                        quoted.add(quoteIdent(n));
                    }
                    sb.append("SELECT ").append(String.join(", ", quoted)).append(" FROM (\n");
                } else {
                    sb.append("SELECT * FROM (\n");
                }
                sb.append("  SELECT t.*, ROW_NUMBER() OVER (PARTITION BY ")
                        .append(String.join(", ", parts))
                        .append(" ORDER BY 1 ").append(order).append(") AS _lh_rn\n");
                sb.append("  FROM (\n").append(indent(inner, 4)).append("\n  ) t\n");
                sb.append(") d WHERE _lh_rn = 1");
            }
        }
        return sb.toString();
    }

    /**
     * Spark SQL：从 JDBC 读上游落地表，清洗后写入 {@code lh_clean_*}。
     * <p>凭证占位 {@code LH_JDBC_*}（与源库同库中间表）。</p>
     */
    public static String compileSparkJdbcPersist(JSONObject conf, String upstreamTable, String cleanTable) {
        if (conf == null) {
            conf = new JSONObject();
        }
        String up = firstNonBlank(upstreamTable, conf.getStr("_lhUpstreamTable"));
        if (StrUtil.isBlank(up)) {
            return compile(conf, up);
        }
        String upBare = LhStagingTables.bareTable(up);
        // 约定：清洗落表与上游 ODS 同源后缀对齐（lh_ods_dev_log → lh_clean_dev_log）
        String outBare = LhStagingTables.bareTable(LhStagingTables.cleanTableFromUpstream(up));
        if (StrUtil.isBlank(outBare) || "lh_clean_t".equalsIgnoreCase(outBare)
                || "lh_clean_staging".equalsIgnoreCase(outBare)) {
            String fallback = LhStagingTables.bareTable(firstNonBlank(cleanTable, conf.getStr("_lhCleanTable")));
            if (StrUtil.isNotBlank(fallback) && fallback.toLowerCase(java.util.Locale.ROOT).startsWith("lh_clean_")
                    && !"lh_clean_t".equalsIgnoreCase(fallback)) {
                outBare = fallback;
            }
        }
        String selectBody = compile(conf, "_lh_ods");
        // 取「首个顶层 SELECT」；勿用 lastIndexOf——dedup 嵌套时会截成半截 SQL
        String selectOnly = extractSelectSql(selectBody);

        StringBuilder sb = new StringBuilder();
        sb.append("-- clean persist ").append(upBare).append(" → ").append(outBare).append('\n');
        sb.append("CREATE OR REPLACE TEMPORARY VIEW _lh_ods\n");
        sb.append("USING jdbc\nOPTIONS (\n");
        sb.append("  url '${LH_JDBC_URL}',\n");
        sb.append("  dbtable '").append(escOpt(up.contains(".") ? stripDbPrefix(up) : upBare)).append("',\n");
        sb.append("  user '${LH_JDBC_USER}',\n");
        sb.append("  password '${LH_JDBC_PASSWORD}',\n");
        sb.append("  driver 'com.mysql.cj.jdbc.Driver'\n");
        sb.append(");\n");
        sb.append("CREATE OR REPLACE TEMPORARY VIEW _lh_clean_sink\n");
        sb.append("USING jdbc\nOPTIONS (\n");
        sb.append("  url '${LH_JDBC_URL}',\n");
        sb.append("  dbtable '").append(escOpt(outBare)).append("',\n");
        sb.append("  user '${LH_JDBC_USER}',\n");
        sb.append("  password '${LH_JDBC_PASSWORD}',\n");
        sb.append("  driver 'com.mysql.cj.jdbc.Driver',\n");
        // 可重跑：Overwrite + truncate，避免 PRIMARY 重复
        sb.append("  truncate 'true'\n");
        sb.append(");\n");
        sb.append("INSERT OVERWRITE TABLE _lh_clean_sink\n");
        sb.append(selectOnly);
        if (!selectOnly.trim().endsWith(";")) {
            sb.append(';');
        }
        sb.append('\n');
        return sb.toString();
    }

    /** 跳过注释后取首个 SELECT…（含 dedup 外层整段） */
    static String extractSelectSql(String body) {
        if (StrUtil.isBlank(body)) {
            return "SELECT 1";
        }
        String[] lines = body.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        boolean started = false;
        for (String line : lines) {
            String t = line.trim();
            if (!started) {
                if (t.isEmpty() || t.startsWith("--")) {
                    continue;
                }
                if (t.toLowerCase(java.util.Locale.ROOT).startsWith("select")) {
                    started = true;
                    sb.append(line);
                }
                continue;
            }
            sb.append('\n').append(line);
        }
        return started ? sb.toString().trim() : body.trim();
    }

    /** 从未被 fieldRules 改写的列名（来自 _lhColumns / columns），用于落表透传 */
    private static List<String> passthroughColumns(JSONObject conf, java.util.Set<String> ruled) {
        List<String> out = new ArrayList<>();
        if (conf == null) {
            return out;
        }
        JSONArray cols = conf.getJSONArray("_lhColumns");
        if (cols == null || cols.isEmpty()) {
            cols = conf.getJSONArray("columns");
        }
        if (cols == null) {
            return out;
        }
        java.util.Set<String> ruledLower = new java.util.LinkedHashSet<>();
        for (String r : ruled) {
            if (r != null) {
                ruledLower.add(r.toLowerCase(java.util.Locale.ROOT));
            }
        }
        for (int i = 0; i < cols.size(); i++) {
            Object o = cols.get(i);
            String name = null;
            if (o instanceof JSONObject jo) {
                name = firstNonBlank(jo.getStr("name"), jo.getStr("column"), jo.getStr("field"));
            } else if (o != null) {
                name = String.valueOf(o).trim();
            }
            if (StrUtil.isBlank(name)) {
                continue;
            }
            if (ruledLower.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            out.add(name.trim());
        }
        return out;
    }

    private static String stripDbPrefix(String fq) {
        String t = StrUtil.blankToDefault(fq, "").replace("`", "");
        int dot = t.indexOf('.');
        return dot > 0 ? t.substring(dot + 1) : t;
    }

    private static String escOpt(String s) {
        return StrUtil.blankToDefault(s, "t").replace("'", "\\'");
    }

    private static String compileField(String field, JSONObject r) {
        String expr = quoteIdent(field);
        JSONArray ops = r.getJSONArray("ops");
        if (ops != null) {
            for (int i = 0; i < ops.size(); i++) {
                String op = StrUtil.blankToDefault(ops.getStr(i), "").trim().toLowerCase();
                expr = switch (op) {
                    case "trim", "trimspaces" -> "TRIM(" + expr + ")";
                    case "lower", "tolowercase", "tolower" -> "LOWER(" + expr + ")";
                    case "upper", "touppercase", "toupper" -> "UPPER(" + expr + ")";
                    case "nullfill", "coalesce" -> {
                        String def = StrUtil.nullToDefault(r.getStr("nullDefault"), "");
                        yield "COALESCE(" + expr + ", '" + escapeLit(def) + "')";
                    }
                    default -> expr;
                };
            }
        }
        // 以下能力必须勾选对应 ops 才生效（表单可能残留 maskRule/lenMax/castType 默认值）
        if (containsOp(ops, "nullfill") || containsOp(ops, "coalesce")) {
            // already applied in switch; keep no-op
        } else if (StrUtil.isNotBlank(r.getStr("nullDefault")) && ops == null) {
            // 无 ops 的极旧数据：仅有 nullDefault 时仍填充
            expr = "COALESCE(" + expr + ", '" + escapeLit(r.getStr("nullDefault")) + "')";
        }
        if (containsOp(ops, "regex") && StrUtil.isNotBlank(r.getStr("regexPat"))) {
            String regexRep = StrUtil.nullToDefault(r.getStr("regexRep"), "");
            expr = "REGEXP_REPLACE(" + expr + ", '" + escapeLit(r.getStr("regexPat")) + "', '"
                    + escapeLit(regexRep) + "')";
        }
        if (containsOp(ops, "mask")) {
            String mask = StrUtil.blankToDefault(r.getStr("maskRule"), "mask_middle");
            if (!"none".equalsIgnoreCase(mask)) {
                expr = applyMask(expr, mask);
            }
        }
        if (containsOp(ops, "lentrim") || containsOp(ops, "len_trim")) {
            Integer lenMax = r.getInt("lenMax");
            if (lenMax != null && lenMax > 0) {
                expr = "SUBSTRING(" + expr + ", 1, " + lenMax + ")";
            }
        }
        if (containsOp(ops, "typecast") || containsOp(ops, "type_cast") || containsOp(ops, "cast")) {
            String castType = StrUtil.trim(r.getStr("castType"));
            if (StrUtil.isNotBlank(castType)) {
                expr = "CAST(" + expr + " AS " + sanitizeType(castType) + ")";
            }
        }
        return expr;
    }

    private static String applyMask(String expr, String maskRule) {
        String m = maskRule.toLowerCase();
        return switch (m) {
            case "mask_middle", "middle" ->
                    "CONCAT(SUBSTRING(CAST(" + expr + " AS STRING), 1, 3), '****', "
                            + "SUBSTRING(CAST(" + expr + " AS STRING), GREATEST(LENGTH(CAST(" + expr + " AS STRING)) - 3, 1)))";
            case "mask_all", "all" -> "'****'";
            case "mask_keep_tail", "keep_tail" ->
                    "CONCAT('****', SUBSTRING(CAST(" + expr + " AS STRING), GREATEST(LENGTH(CAST(" + expr + " AS STRING)) - 3, 1)))";
            default -> expr;
        };
    }

    private static boolean containsOp(JSONArray ops, String name) {
        if (ops == null || StrUtil.isBlank(name)) {
            return false;
        }
        for (int i = 0; i < ops.size(); i++) {
            if (name.equalsIgnoreCase(StrUtil.blankToDefault(ops.getStr(i), ""))) {
                return true;
            }
        }
        return false;
    }

    private static String sanitizeType(String t) {
        String u = t.trim().toUpperCase().replaceAll("[^A-Z0-9_(),\\s]", "");
        if (u.startsWith("VARCHAR") || u.startsWith("STRING") || u.startsWith("CHAR")) {
            return u.contains("(") ? u : "STRING";
        }
        if (u.startsWith("INT") || u.equals("BIGINT") || u.equals("SMALLINT") || u.equals("TINYINT")) {
            return u.startsWith("INT") ? "INT" : u;
        }
        if (u.startsWith("DECIMAL") || u.startsWith("NUMERIC") || u.equals("DOUBLE") || u.equals("FLOAT")
                || u.equals("BOOLEAN") || u.equals("DATE") || u.equals("TIMESTAMP")) {
            return u;
        }
        return "STRING";
    }

    private static String quoteIdent(String s) {
        return "`" + s.replace("`", "") + "`";
    }

    private static String escapeLit(String s) {
        return StrUtil.nullToDefault(s, "").replace("'", "''");
    }

    private static String indent(String sql, int spaces) {
        String pad = " ".repeat(Math.max(spaces, 0));
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
