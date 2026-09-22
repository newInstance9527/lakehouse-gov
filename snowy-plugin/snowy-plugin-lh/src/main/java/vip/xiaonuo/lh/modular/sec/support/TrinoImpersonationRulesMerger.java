package vip.xiaonuo.lh.modular.sec.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 将门户主体白名单合并进 Trino file-based access-control {@code rules.json}。
 * 只替换 {@code impersonation}；保留 catalogs/schemas/tables 等其它段。
 */
public final class TrinoImpersonationRulesMerger {

    private TrinoImpersonationRulesMerger() {
    }

    /** 无映射主体时永不匹配任何 new_user */
    public static final String EMPTY_NEW_USER = "(?!)";

    public static String buildNewUserPattern(Collection<String> trinoUsers) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (trinoUsers != null) {
            for (String raw : trinoUsers) {
                if (StrUtil.isBlank(raw)) {
                    continue;
                }
                unique.add(raw.trim());
            }
        }
        if (unique.isEmpty()) {
            return EMPTY_NEW_USER;
        }
        StringBuilder alt = new StringBuilder();
        for (String u : unique) {
            if (!alt.isEmpty()) {
                alt.append('|');
            }
            alt.append(Pattern.quote(u));
        }
        return "^(" + alt + ")$";
    }

    public static Map<String, Object> buildImpersonationRule(String originalUser, Collection<String> trinoUsers) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("original_user", StrUtil.blankToDefault(originalUser, "admin").trim());
        rule.put("new_user", buildNewUserPattern(trinoUsers));
        rule.put("allow", true);
        return rule;
    }

    /**
     * 合并：以 existing 为底，整段替换 impersonation。
     * existing 为空时用 {@link #bootstrapBase()}。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> merge(Map<String, Object> existing, List<?> impersonationRules) {
        Map<String, Object> base = existing == null || existing.isEmpty()
                ? bootstrapBase()
                : new LinkedHashMap<>(existing);
        List<Object> rules = new ArrayList<>();
        if (impersonationRules != null) {
            for (Object item : impersonationRules) {
                if (item instanceof Map<?, ?> m) {
                    rules.add(new LinkedHashMap<>((Map<String, Object>) m));
                }
            }
        }
        base.put("impersonation", rules);
        return base;
    }

    /** 首次无文件时的最小可启动模板（与 deploy/trino 权威副本口径一致：其余段放行，impersonation 由调用方写入） */
    public static Map<String, Object> bootstrapBase() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("catalogs", List.of(
                mapOf("user", ".*", "catalog", "system", "allow", "read-only"),
                mapOf("user", ".*", "catalog", ".*", "allow", "all")
        ));
        out.put("schemas", List.of(
                mapOf("user", ".*", "catalog", ".*", "schema", ".*", "owner", true)
        ));
        out.put("tables", List.of(
                mapOf(
                        "user", ".*",
                        "catalog", ".*",
                        "schema", ".*",
                        "table", ".*",
                        "privileges", List.of("SELECT", "INSERT", "DELETE", "UPDATE", "OWNERSHIP", "GRANT_SELECT")
                )
        ));
        out.put("impersonation", List.of());
        return out;
    }

    public static Map<String, Object> parseRulesJson(String json) {
        if (StrUtil.isBlank(json)) {
            return bootstrapBase();
        }
        JSONObject obj = JSONUtil.parseObj(json);
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : obj.keySet()) {
            out.put(key, obj.get(key));
        }
        return out;
    }

    public static String toPrettyJson(Map<String, Object> rules) {
        return JSONUtil.toJsonPrettyStr(rules) + "\n";
    }

    /** 内容指纹，作同步水位 mark_value（sha256 hex） */
    public static String contentMark(String jsonBody) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(StrUtil.blankToDefault(jsonBody, "").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
