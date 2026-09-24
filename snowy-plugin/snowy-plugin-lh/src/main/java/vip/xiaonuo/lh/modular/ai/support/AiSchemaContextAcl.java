package vip.xiaonuo.lh.modular.ai.support;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * AI chat schema linking：规范化客户端 schemaContext，并按 ACL 软剥离未授权表。
 * 不旁路授权；匹配口径与资产源绑定 / omFqn / assetCode 一致。
 */
public final class AiSchemaContextAcl {

    private static final int MAX_TABLES = 8;

    private AiSchemaContextAcl() {
    }

    /**
     * 规范化前端/工具传入的 schema 上下文。
     * 保留 assetId（若有）、schema、table、columns；上限 {@value MAX_TABLES} 张表。
     */
    public static List<Map<String, Object>> normalize(List<Map<String, Object>> raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (Map<String, Object> item : raw) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            String schema = String.valueOf(item.getOrDefault("schema", "")).trim();
            String table = String.valueOf(item.getOrDefault("table",
                    item.getOrDefault("name", ""))).trim();
            if (StrUtil.isBlank(table) || "null".equalsIgnoreCase(table)) {
                String fqn = trimToNull(item.get("fqn"));
                if (fqn != null) {
                    String[] parts = splitFqn(fqn);
                    if (parts != null) {
                        schema = parts[0];
                        table = parts[1];
                    }
                }
            }
            if (StrUtil.isBlank(table) || "null".equalsIgnoreCase(table)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            String assetId = firstAssetId(item);
            if (assetId != null) {
                row.put("assetId", assetId);
            }
            row.put("schema", schema);
            row.put("table", table);
            List<Map<String, Object>> cols = new ArrayList<>();
            Object colsObj = item.get("columns");
            if (colsObj instanceof List<?> list) {
                for (Object c : list) {
                    if (c instanceof Map<?, ?> m) {
                        Object n = m.get("name");
                        if (n == null || StrUtil.isBlank(String.valueOf(n))) {
                            continue;
                        }
                        Map<String, Object> col = new LinkedHashMap<>();
                        col.put("name", String.valueOf(n).trim());
                        if (m.get("type") != null) {
                            col.put("type", String.valueOf(m.get("type")));
                        }
                        cols.add(col);
                    } else if (c != null && StrUtil.isNotBlank(String.valueOf(c))) {
                        cols.add(Map.of("name", String.valueOf(c).trim()));
                    }
                }
            }
            row.put("columns", cols);
            out.add(row);
            if (out.size() >= MAX_TABLES) {
                break;
            }
        }
        return out;
    }

    /**
     * 软剥离：无法解析到资产或当前用户不可读的条目丢弃；不抛错。
     *
     * @param resolveAssetId 解析条目 → assetId；无法绑定返回 null/blank
     * @param canReadAsset   owned ∪ hasTableReadGrant（与 listMyAssets 一致）
     * @return 授权后的上下文；dropped 为丢弃条数（可为 null）
     */
    public static List<Map<String, Object>> retainReadable(
            List<Map<String, Object>> normalized,
            Function<Map<String, Object>, String> resolveAssetId,
            Predicate<String> canReadAsset,
            int[] droppedOut) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (normalized == null || normalized.isEmpty()) {
            if (droppedOut != null && droppedOut.length > 0) {
                droppedOut[0] = 0;
            }
            return out;
        }
        int dropped = 0;
        for (Map<String, Object> item : normalized) {
            if (item == null || item.isEmpty()) {
                dropped++;
                continue;
            }
            String assetId = null;
            if (resolveAssetId != null) {
                assetId = trimToNull(resolveAssetId.apply(item));
            }
            if (assetId == null) {
                assetId = firstAssetId(item);
            }
            if (assetId == null || canReadAsset == null || !canReadAsset.test(assetId)) {
                dropped++;
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>(item);
            row.put("assetId", assetId);
            out.add(row);
        }
        if (droppedOut != null && droppedOut.length > 0) {
            droppedOut[0] = dropped;
        }
        return out;
    }

    /**
     * 表引用是否命中候选标识（omFqn / objectName / assetCode / 短名）。
     * 口径与 ApiBuildTableAccess.objectNameMatches 对齐。
     */
    public static boolean matchesTableRef(String schema, String table, String... candidates) {
        if (StrUtil.isBlank(table) || candidates == null) {
            return false;
        }
        for (String c : candidates) {
            if (StrUtil.isBlank(c)) {
                continue;
            }
            if (objectNameMatches(c.trim(), schema, table)) {
                return true;
            }
        }
        return false;
    }

    /** schema.table / catalog.schema.table / 短名 匹配（与构建台源绑定一致）。 */
    static boolean objectNameMatches(String objectName, String schema, String table) {
        if (StrUtil.isBlank(objectName) || StrUtil.isBlank(table)) {
            return false;
        }
        String on = stripQuotes(objectName);
        String t = stripQuotes(table);
        String s = stripQuotes(schema);
        if (on.equalsIgnoreCase(t)) {
            return true;
        }
        if (StrUtil.isNotBlank(s) && on.equalsIgnoreCase(s + "." + t)) {
            return true;
        }
        String[] parts = on.split("\\.");
        if (parts.length >= 1 && parts[parts.length - 1].equalsIgnoreCase(t)) {
            if (StrUtil.isBlank(s) || parts.length == 1) {
                return true;
            }
            return parts[parts.length - 2].equalsIgnoreCase(s);
        }
        return false;
    }

    private static String stripQuotes(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.length() >= 2) {
            char a = s.charAt(0);
            char b = s.charAt(s.length() - 1);
            if ((a == '"' && b == '"') || (a == '`' && b == '`') || (a == '[' && b == ']')) {
                return s.substring(1, s.length() - 1).trim();
            }
        }
        return s;
    }

    /** 从条目取 assetId / id。 */
    public static String firstAssetId(Map<String, Object> item) {
        if (item == null) {
            return null;
        }
        String id = trimToNull(item.get("assetId"));
        if (id != null) {
            return id;
        }
        return trimToNull(item.get("id"));
    }

    static String trimToNull(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        if (StrUtil.isBlank(s) || "null".equalsIgnoreCase(s)) {
            return null;
        }
        return s;
    }

    /** 拆 catalog.schema.table / schema.table → [schema, table]；仅一段则 schema 空。 */
    static String[] splitFqn(String fqn) {
        if (StrUtil.isBlank(fqn)) {
            return null;
        }
        String[] parts = fqn.trim().split("\\.");
        if (parts.length == 0) {
            return null;
        }
        String table = parts[parts.length - 1].trim();
        if (StrUtil.isBlank(table)) {
            return null;
        }
        String schema = parts.length >= 2 ? parts[parts.length - 2].trim() : "";
        return new String[]{schema, table};
    }

    /** 规范化比较用 FQN 键。 */
    public static String fqnKey(String schema, String table) {
        String s = StrUtil.blankToDefault(schema, "").trim();
        String t = StrUtil.blankToDefault(table, "").trim();
        if (StrUtil.isBlank(t)) {
            return "";
        }
        return (StrUtil.isBlank(s) ? t : s + "." + t).toLowerCase(Locale.ROOT);
    }
}
