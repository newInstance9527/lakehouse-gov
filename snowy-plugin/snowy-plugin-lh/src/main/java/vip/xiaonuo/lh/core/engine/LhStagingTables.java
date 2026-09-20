package vip.xiaonuo.lh.core.engine;

import cn.hutool.core.util.StrUtil;

/**
 * DAG 节点间落地表命名（MySQL/PG 等 RDB 中间表）。
 * <p>源 → {@code lh_ods_*}；清洗 → {@code lh_clean_*}。DS 只编排，行数据经这些表传递。</p>
 */
public final class LhStagingTables {

    private LhStagingTables() {
    }

    public static String bareTable(String table) {
        if (StrUtil.isBlank(table)) {
            return "";
        }
        String t = table.trim();
        int dot = t.lastIndexOf('.');
        if (dot >= 0 && dot < t.length() - 1) {
            t = t.substring(dot + 1);
        }
        return t.replace("`", "").replace("\"", "").trim();
    }

    public static String odsTable(String bare) {
        return "lh_ods_" + sanitize(stripStagingPrefix(requireBare(bare, "ods")));
    }

    public static String cleanTable(String bare) {
        return "lh_clean_" + sanitize(stripStagingPrefix(requireBare(bare, "clean")));
    }

    /**
     * 从上游 ODS 落表名推导清洗表：{@code lh_ods_dev_log} → {@code lh_clean_dev_log}。
     * 禁止落到无业务含义的 {@code lh_clean_t}。
     */
    public static String cleanTableFromUpstream(String upstreamOdsOrBare) {
        return cleanTable(upstreamOdsOrBare);
    }

    /** 去掉已有层前缀，避免 lh_clean_lh_ods_* 双重前缀 */
    public static String stripStagingPrefix(String bare) {
        String b = bareTable(bare);
        String lower = b.toLowerCase();
        if (lower.startsWith("lh_ods_")) {
            return b.substring("lh_ods_".length());
        }
        if (lower.startsWith("lh_clean_")) {
            return b.substring("lh_clean_".length());
        }
        return b;
    }

    /** database.table；database 空则仅表名 */
    public static String qualify(String database, String tableOnly) {
        String t = StrUtil.blankToDefault(tableOnly, "").replace("`", "");
        if (StrUtil.isBlank(t)) {
            t = "lh_staging";
        }
        if (StrUtil.isBlank(database)) {
            return t;
        }
        return database.replace("`", "") + "." + t;
    }

    public static String sanitize(String raw) {
        // 中间表名统一小写蛇形，与库内物理表约定一致（字段名不做大小写折叠）
        String s = StrUtil.blankToDefault(raw, "").toLowerCase().replaceAll("[^a-z0-9_]", "_");
        if (s.isEmpty() || Character.isDigit(s.charAt(0))) {
            s = "t_" + s;
        }
        if ("t".equals(s) || "t_".equals(s)) {
            s = "staging";
        }
        return s.length() > 48 ? s.substring(0, 48) : s;
    }

    private static String requireBare(String bare, String kind) {
        String b = bareTable(bare);
        if (StrUtil.isBlank(b) || "t".equalsIgnoreCase(b)) {
            return "staging";
        }
        return b;
    }
}
