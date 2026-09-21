package vip.xiaonuo.lh.modular.compliance.support;

import vip.xiaonuo.lh.modular.lifecycle.support.GovLcMetadataSql;

/**
 * 合规 Iceberg 硬删与反查。谓词只允许主体索引列 = HMAC，不拼接自由 SQL。
 */
public final class GovDelIcebergSql {

    private static final java.util.regex.Pattern HASH = java.util.regex.Pattern.compile("^[a-fA-F0-9]{32,128}$");

    private GovDelIcebergSql() {
    }

    public static String delete(String schema, String table, String column, String subjectHash) {
        check(schema, table, column, subjectHash);
        return "DELETE FROM " + GovLcMetadataSql.quote(schema) + "." + GovLcMetadataSql.quote(table)
                + " WHERE " + GovLcMetadataSql.quote(column) + " = '" + subjectHash + "'";
    }

    public static String count(String schema, String table, String column, String subjectHash) {
        check(schema, table, column, subjectHash);
        return "SELECT count(*) AS cnt FROM " + GovLcMetadataSql.quote(schema) + "." + GovLcMetadataSql.quote(table)
                + " WHERE " + GovLcMetadataSql.quote(column) + " = '" + subjectHash + "'";
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
