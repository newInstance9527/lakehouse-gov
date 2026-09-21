package vip.xiaonuo.lh.modular.query.support;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 即席 SQL 命名参数：支持 {@code :name} 与 {@code ${name}}（跳过字符串字面量）。
 */
public final class CpQueryParamBinder {

    private static final Pattern TOKEN = Pattern.compile(
            "'(?:''|[^'])*'"
                    + "|\"(?:\\\\.|[^\"\\\\])*\""
                    + "|:([A-Za-z_][A-Za-z0-9_]*)"
                    + "|\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}");

    private CpQueryParamBinder() {
    }

    public static List<String> detectParams(String sql) {
        Set<String> names = new LinkedHashSet<>();
        if (StrUtil.isBlank(sql)) {
            return List.of();
        }
        Matcher m = TOKEN.matcher(sql);
        while (m.find()) {
            String n = m.group(1) != null ? m.group(1) : m.group(2);
            if (StrUtil.isNotBlank(n)) {
                names.add(n);
            }
        }
        return new ArrayList<>(names);
    }

    public static String bind(String sql, Map<String, ?> params) {
        if (StrUtil.isBlank(sql)) {
            return sql;
        }
        List<String> needed = detectParams(sql);
        if (needed.isEmpty()) {
            return sql;
        }
        Map<String, ?> p = params == null ? Map.of() : params;
        List<String> missing = new ArrayList<>();
        for (String n : needed) {
            if (!p.containsKey(n) || p.get(n) == null || StrUtil.isBlank(String.valueOf(p.get(n)))) {
                missing.add(n);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("缺少命名参数: " + String.join(", ", missing));
        }
        StringBuffer sb = new StringBuffer();
        Matcher m = TOKEN.matcher(sql);
        while (m.find()) {
            String n = m.group(1) != null ? m.group(1) : m.group(2);
            if (n == null) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(literal(p.get(n))));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static Map<String, Object> preview(String sql, Map<String, ?> params) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> names = detectParams(sql);
        out.put("params", names);
        try {
            out.put("boundSql", bind(sql, params));
            out.put("ok", true);
        } catch (IllegalArgumentException e) {
            out.put("ok", false);
            out.put("message", e.getMessage());
            out.put("boundSql", sql);
        }
        return out;
    }

    private static String literal(Object raw) {
        if (raw == null) {
            return "NULL";
        }
        if (raw instanceof Number || raw instanceof Boolean) {
            return String.valueOf(raw);
        }
        String s = String.valueOf(raw).trim();
        if ("null".equalsIgnoreCase(s)) {
            return "NULL";
        }
        if (s.matches("^-?\\d+(\\.\\d+)?$")) {
            return s;
        }
        if ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\""))) {
            return "'" + s.substring(1, s.length() - 1).replace("'", "''") + "'";
        }
        return "'" + s.replace("'", "''") + "'";
    }
}
