package vip.xiaonuo.lh.modular.compliance.support;

import vip.xiaonuo.lh.modular.lifecycle.support.GovLcMetadataSql;

/**
 * 合规 ClickHouse SQL：COUNT / ALTER DELETE（mutation）/ 副本 is_done 校验。
 * 谓词只允许主体索引列 = HMAC，不拼接自由 SQL。
 */
public final class GovDelCkSql {

    private static final java.util.regex.Pattern HASH = java.util.regex.Pattern.compile("^[a-fA-F0-9]{32,128}$");
    private static final java.util.regex.Pattern MUTATION_ID = java.util.regex.Pattern.compile("^[0-9A-Za-z_\\-]{1,64}$");

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

    /**
     * 合规硬删：{@code ALTER … DELETE … SETTINGS mutations_sync = 2}。
     * {@code cluster} 非空时追加 {@code ON CLUSTER}。
     */
    public static String alterDelete(String schema, String table, String column, String subjectHash, String cluster) {
        check(schema, table, column, subjectHash);
        StringBuilder sb = new StringBuilder();
        sb.append("ALTER TABLE ").append(quote(schema)).append('.').append(quote(table));
        if (cluster != null && !cluster.isBlank()) {
            if (!GovLcMetadataSql.isIdent(cluster)) {
                throw new IllegalArgumentException("非法 cluster: " + cluster);
            }
            sb.append(" ON CLUSTER ").append(cluster);
        }
        sb.append(" DELETE WHERE ").append(quote(column)).append(" = '").append(subjectHash).append("'");
        sb.append(" SETTINGS mutations_sync = 2");
        return sb.toString();
    }

    /** 取该表最新 mutation（execute 后登记 mutation_id）。 */
    public static String latestMutation(String table) {
        if (!GovLcMetadataSql.isIdent(table)) {
            throw new IllegalArgumentException("非法表名: " + table);
        }
        return "SELECT mutation_id, is_done, latest_failed_part FROM system.mutations WHERE table = '"
                + table + "' ORDER BY create_time DESC LIMIT 1";
    }

    /**
     * 全副本 is_done 校验。无 cluster 时查本机 {@code system.mutations}。
     */
    public static String mutationStatus(String cluster, String table, String mutationId) {
        if (!GovLcMetadataSql.isIdent(table)) {
            throw new IllegalArgumentException("非法表名: " + table);
        }
        if (mutationId == null || !MUTATION_ID.matcher(mutationId).matches()) {
            throw new IllegalArgumentException("非法 mutation_id");
        }
        if (cluster == null || cluster.isBlank()) {
            return "SELECT is_done, parts_to_do FROM system.mutations WHERE table = '"
                    + table + "' AND mutation_id = '" + mutationId + "'";
        }
        if (!GovLcMetadataSql.isIdent(cluster)) {
            throw new IllegalArgumentException("非法 cluster: " + cluster);
        }
        return "SELECT hostName() AS host, is_done, parts_to_do FROM clusterAllReplicas('"
                + cluster + "', system.mutations) WHERE table = '" + table
                + "' AND mutation_id = '" + mutationId + "'";
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
