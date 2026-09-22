package vip.xiaonuo.lh.modular.compliance.support;

import vip.xiaonuo.lh.modular.lifecycle.support.GovLcMetadataSql;

/**
 * 合规 ClickHouse 命中统计。谓词只允许主体索引列 = HMAC，不拼接自由 SQL。
 */
public final class GovDelCkSql {

    private static final java.util.regex.Pattern HASH = java.util.regex.Pattern.compile("^[a-fA-F0-9]{32,128}$");

    private GovDelCkSql() {
    }

    /**
     * @return schema / table；FQN 可为 {@code schema.table} 或 {@code clickhouse.schema.table}
     */
    public static GovLcMetadataSql.TableRef parse(String fqn) {
        if (fqn == null || fqn.isBlank()) {
            throw new IllegalArgumentException("表名为空");
        }
        String[] parts = fqn.trim().split("\\.");
        for (String p : parts) {
            if (!GovLcMetadataSql.isIdent(p)) {
                throw new IllegalArgumentException("非法表名: " + fqn);
            }
        }
        if (parts.length == 2) {
            return new GovLcMetadataSql.TableRef("clickhouse", parts[0], parts[1]);
        }
        if (parts.length == 3) {
            return new GovLcMetadataSql.TableRef(parts[0], parts[1], parts[2]);
        }
        throw new IllegalArgumentException("CK 表名须为 schema.table 或 catalog.schema.table: " + fqn);
    }

    public static String count(String schema, String table, String column, String subjectHash) {
        check(schema, table, column, subjectHash);
        return "SELECT count(*) AS cnt FROM " + quote(schema) + "." + quote(table)
                + " WHERE " + quote(column) + " = '" + subjectHash + "'";
    }

    static String quote(String ident) {
        return "`" + ident.replace("`", "") + "`";
    }

    private static void check(String schema, String table, String column, String subjectHash) {
        if (!GovLcMetadataSql.isIdent(schema) || !GovLcMetadataSql.isIdent(table) || !GovLcMetadataSql.isIdent(column)) {
            throw new IllegalArgumentException("非法标识符");
        }
        if (subjectHash == null || !HASH.matcher(subjectHash).matches()) {
            throw new IllegalArgumentException("主体摘要不是合法 HMAC hex");
        }
    }
}
