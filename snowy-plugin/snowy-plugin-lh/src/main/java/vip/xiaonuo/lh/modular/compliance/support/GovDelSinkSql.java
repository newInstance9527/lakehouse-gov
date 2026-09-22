package vip.xiaonuo.lh.modular.compliance.support;

import vip.xiaonuo.lh.modular.lifecycle.support.GovLcMetadataSql;

/**
 * 回流 / 源端 sink FQN 解析与 JDBC 删除 SQL 校验。
 * FQN：{@code engine.dsHint.table}（如 {@code mysql.crm.user_profile}）。
 */
public final class GovDelSinkSql {

    private static final java.util.regex.Pattern HASH = java.util.regex.Pattern.compile("^[a-fA-F0-9]{32,128}$");

    private GovDelSinkSql() {
    }

    public record SinkRef(String engine, String dsHint, String table) {
    }

    public static SinkRef parse(String fqn) {
        if (fqn == null || fqn.isBlank()) {
            throw new IllegalArgumentException("sink 对象为空");
        }
        String[] parts = fqn.trim().split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("sink FQN 须为 engine.dsHint.table: " + fqn);
        }
        for (String p : parts) {
            if (!GovLcMetadataSql.isIdent(p)) {
                throw new IllegalArgumentException("非法 sink FQN: " + fqn);
            }
        }
        return new SinkRef(parts[0].toLowerCase(java.util.Locale.ROOT), parts[1], parts[2]);
    }

    public static boolean isRdb(String engine) {
        if (engine == null) {
            return false;
        }
        String e = engine.toLowerCase(java.util.Locale.ROOT);
        return e.equals("mysql") || e.equals("mariadb") || e.equals("pg") || e.equals("postgres")
                || e.equals("postgresql") || e.equals("doris") || e.equals("oracle") || e.equals("sqlserver");
    }

    public static boolean isRedis(String engine) {
        return engine != null && "redis".equalsIgnoreCase(engine);
    }

    public static boolean isElasticsearch(String engine) {
        if (engine == null) {
            return false;
        }
        String e = engine.toLowerCase(java.util.Locale.ROOT);
        return e.equals("es") || e.equals("elasticsearch");
    }

    /** 仅用于展示 / 审计；真执行走 PreparedStatement。 */
    public static String deletePreview(String schema, String table, String column, String subjectHash) {
        check(schema, table, column, subjectHash);
        return "DELETE FROM " + quote(schema) + "." + quote(table)
                + " WHERE " + quote(column) + " = ? /* " + subjectHash.substring(0, 8) + "… */";
    }

    public static String countPreview(String schema, String table, String column) {
        if (!GovLcMetadataSql.isIdent(schema) || !GovLcMetadataSql.isIdent(table)
                || !GovLcMetadataSql.isIdent(column)) {
            throw new IllegalArgumentException("非法标识符");
        }
        return "SELECT count(*) AS cnt FROM " + quote(schema) + "." + quote(table)
                + " WHERE " + quote(column) + " = ?";
    }

    public static void assertHash(String subjectHash) {
        if (subjectHash == null || !HASH.matcher(subjectHash).matches()) {
            throw new IllegalArgumentException("主体摘要不是合法 HMAC hex");
        }
    }

    static String quote(String ident) {
        return "`" + ident.replace("`", "") + "`";
    }

    private static void check(String schema, String table, String column, String subjectHash) {
        if (!GovLcMetadataSql.isIdent(schema) || !GovLcMetadataSql.isIdent(table) || !GovLcMetadataSql.isIdent(column)) {
            throw new IllegalArgumentException("非法标识符");
        }
        assertHash(subjectHash);
    }
}
