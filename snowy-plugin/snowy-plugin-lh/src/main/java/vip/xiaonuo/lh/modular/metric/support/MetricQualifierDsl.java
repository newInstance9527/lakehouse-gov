package vip.xiaonuo.lh.modular.metric.support;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 修饰词白名单谓词 DSL → SQL 片段。
 * 允许：col op literal；op ∈ (=, !=, &lt;&gt;, IN, NOT IN, IS NULL, IS NOT NULL, BETWEEN, LIKE)
 * 禁止：子查询、分号、注释、多语句、任意函数嵌套。
 */
public final class MetricQualifierDsl {

    private static final Pattern IDENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Pattern NUM = Pattern.compile("^-?\\d+(\\.\\d+)?$");
    private static final Pattern STR = Pattern.compile("^'(?:[^']|'')*'$");
    private static final Pattern FORBIDDEN = Pattern.compile(
            "(?i)(--|/\\*|\\*/|;|\\bunion\\b|\\bselect\\b|\\binsert\\b|\\bupdate\\b|\\bdelete\\b|\\bdrop\\b|\\bexec\\b|\\bcall\\b)");

    private MetricQualifierDsl() {
    }

    public static String compile(String dsl) {
        if (StrUtil.isBlank(dsl)) {
            throw new IllegalArgumentException("修饰词 DSL 不能为空");
        }
        String raw = dsl.trim().replaceAll("\\s+", " ");
        if (FORBIDDEN.matcher(raw).find()) {
            throw new IllegalArgumentException("修饰词 DSL 含禁止片段（注释/分号/SQL 关键字）");
        }
        if (raw.contains("(") && !raw.toUpperCase(Locale.ROOT).matches(".*\\b(IN|NOT IN)\\b.*")) {
            // BETWEEN / 比较无括号；仅 IN 列表允许括号
            if (!raw.toUpperCase(Locale.ROOT).contains(" IN (")) {
                throw new IllegalArgumentException("修饰词 DSL 禁止括号函数/子查询");
            }
        }

        String upper = raw.toUpperCase(Locale.ROOT);
        if (upper.endsWith(" IS NULL") || upper.endsWith(" IS NOT NULL")) {
            boolean not = upper.endsWith(" IS NOT NULL");
            String col = raw.substring(0, raw.length() - (not ? " IS NOT NULL".length() : " IS NULL".length())).trim();
            assertIdent(col, "列名");
            return quoteIdent(col) + (not ? " IS NOT NULL" : " IS NULL");
        }

        Matcher between = Pattern.compile(
                "^([A-Za-z_][A-Za-z0-9_]*)\\s+BETWEEN\\s+(.+?)\\s+AND\\s+(.+)$",
                Pattern.CASE_INSENSITIVE).matcher(raw);
        if (between.matches()) {
            String col = between.group(1);
            String a = between.group(2).trim();
            String b = between.group(3).trim();
            assertIdent(col, "列名");
            return quoteIdent(col) + " BETWEEN " + lit(a) + " AND " + lit(b);
        }

        Matcher in = Pattern.compile(
                "^([A-Za-z_][A-Za-z0-9_]*)\\s+(NOT\\s+IN|IN)\\s*\\((.+)\\)$",
                Pattern.CASE_INSENSITIVE).matcher(raw);
        if (in.matches()) {
            String col = in.group(1);
            String op = in.group(2).toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
            String body = in.group(3).trim();
            assertIdent(col, "列名");
            List<String> parts = splitLiterals(body);
            if (parts.isEmpty()) {
                throw new IllegalArgumentException("IN 列表不能为空");
            }
            List<String> litParts = new ArrayList<>();
            for (String p : parts) {
                litParts.add(lit(p.trim()));
            }
            return quoteIdent(col) + " " + op + " (" + String.join(", ", litParts) + ")";
        }

        Matcher cmp = Pattern.compile(
                "^([A-Za-z_][A-Za-z0-9_]*)\\s*(=|!=|<>|LIKE)\\s*(.+)$",
                Pattern.CASE_INSENSITIVE).matcher(raw);
        if (cmp.matches()) {
            String col = cmp.group(1);
            String op = cmp.group(2);
            if ("<>".equals(op)) {
                op = "!=";
            }
            String rhs = cmp.group(3).trim();
            assertIdent(col, "列名");
            if ("LIKE".equalsIgnoreCase(op)) {
                return quoteIdent(col) + " LIKE " + lit(rhs);
            }
            return quoteIdent(col) + " " + op + " " + lit(rhs);
        }

        throw new IllegalArgumentException(
                "无法解析修饰词 DSL，示例：status = 'OK' / amt BETWEEN 1 AND 10 / ch IN ('a','b') / dt IS NOT NULL");
    }

    /** 校验已落库的 SQL 片段仍安全（ver 快照回放） */
    public static void assertSafeSqlFragment(String sql) {
        if (StrUtil.isBlank(sql)) {
            throw new IllegalArgumentException("限定 SQL 片段为空");
        }
        if (FORBIDDEN.matcher(sql).find()) {
            throw new IllegalArgumentException("限定 SQL 片段含禁止内容");
        }
    }

    private static void assertIdent(String name, String what) {
        if (!IDENT.matcher(name).matches()) {
            throw new IllegalArgumentException(what + "非法: " + name);
        }
    }

    private static String lit(String v) {
        if (NUM.matcher(v).matches()) {
            return v;
        }
        if (STR.matcher(v).matches()) {
            return v;
        }
        // 裸词 → 当作字符串
        if (IDENT.matcher(v).matches()) {
            return "'" + v.replace("'", "''") + "'";
        }
        throw new IllegalArgumentException("字面量非法: " + v);
    }

    private static List<String> splitLiterals(String body) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inStr = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\'') {
                inStr = !inStr;
                cur.append(c);
                continue;
            }
            if (c == ',' && !inStr) {
                out.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    private static String quoteIdent(String col) {
        return "\"" + col + "\"";
    }
}
