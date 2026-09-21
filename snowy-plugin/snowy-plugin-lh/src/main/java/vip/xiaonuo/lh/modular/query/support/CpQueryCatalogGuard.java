package vip.xiaonuo.lh.modular.query.support;

import cn.hutool.core.util.StrUtil;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 SQL / 会话上下文提取 catalog，供即席查询面校验。
 */
public final class CpQueryCatalogGuard {

    private static final Pattern THREE_PART = Pattern.compile(
            "(?i)(?:\\bfrom|\\bjoin|\\btable)\\s+"
                    + "(?:\"([^\"]+)\"|`([^`]+)`|([A-Za-z_][\\w$]*))"
                    + "\\s*\\.\\s*"
                    + "(?:\"([^\"]+)\"|`([^`]+)`|([A-Za-z_][\\w$]*))"
                    + "\\s*\\.\\s*"
                    + "(?:\"([^\"]+)\"|`([^`]+)`|([A-Za-z_][\\w$]*))");

    private static final Pattern USE_CATALOG = Pattern.compile(
            "(?i)\\buse\\s+(?:\"([^\"]+)\"|`([^`]+)`|([A-Za-z_][\\w$]*))\\s*(?:;|$)");

    private CpQueryCatalogGuard() {
    }

    /**
     * @param sessionCatalog 请求参数 / 默认 catalog
     * @return 需校验的 catalog 集合（小写键对应原文）
     */
    public static Set<String> extractCatalogs(String sql, String sessionCatalog) {
        Set<String> out = new LinkedHashSet<>();
        if (StrUtil.isNotBlank(sessionCatalog)) {
            out.add(sessionCatalog.trim());
        }
        if (StrUtil.isBlank(sql)) {
            return out;
        }
        Matcher m = THREE_PART.matcher(sql);
        while (m.find()) {
            String cat = firstNonBlank(m.group(1), m.group(2), m.group(3));
            if (StrUtil.isNotBlank(cat)) {
                out.add(cat.trim());
            }
        }
        Matcher use = USE_CATALOG.matcher(sql);
        while (use.find()) {
            String cat = firstNonBlank(use.group(1), use.group(2), use.group(3));
            if (StrUtil.isNotBlank(cat)) {
                out.add(cat.trim());
            }
        }
        return out;
    }

    /**
     * @return 第一个不允许的 catalog；全部允许则 null
     */
    public static String firstDenied(String sql, String sessionCatalog, CpTrinoQueryCatalogService surface) {
        for (String cat : extractCatalogs(sql, sessionCatalog)) {
            if (!surface.isCatalogAllowed(cat)) {
                return cat;
            }
        }
        return null;
    }

    public static boolean looksLikeRegistrationOnly(String catalog) {
        if (StrUtil.isBlank(catalog)) {
            return false;
        }
        return catalog.trim().toLowerCase(Locale.ROOT).startsWith("ds_");
    }

    private static String firstNonBlank(String a, String b, String c) {
        if (StrUtil.isNotBlank(a)) {
            return a;
        }
        if (StrUtil.isNotBlank(b)) {
            return b;
        }
        return c;
    }
}
