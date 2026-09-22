package vip.xiaonuo.lh.modular.lifecycle.support;

/**
 * Trino 读 Iceberg 元数据表的 SQL（{@code $files} / {@code $all_files} / {@code $snapshots} / {@code $partitions}）。
 * 不扫数据层。
 */
public final class GovLcMetadataSql {

    public static final long SMALL_FILE_BYTES = 32L * 1024 * 1024;

    private GovLcMetadataSql() {
    }

    public record TableRef(String catalog, String schema, String table) {
    }

    public static TableRef parse(String fqn, String defaultCatalog) {
        if (fqn == null || fqn.isBlank()) {
            throw new IllegalArgumentException("表名为空");
        }
        String[] parts = fqn.trim().split("\\.");
        for (String p : parts) {
            if (!isIdent(p)) {
                throw new IllegalArgumentException("非法表名: " + fqn);
            }
        }
        String catalog = (defaultCatalog == null || defaultCatalog.isBlank()) ? "iceberg" : defaultCatalog.trim();
        if (!isIdent(catalog)) {
            throw new IllegalArgumentException("非法 catalog: " + catalog);
        }
        if (parts.length == 2) {
            return new TableRef(catalog, parts[0], parts[1]);
        }
        if (parts.length == 3) {
            return new TableRef(parts[0], parts[1], parts[2]);
        }
        throw new IllegalArgumentException("表名须为 schema.table 或 catalog.schema.table: " + fqn);
    }

    public static String files(TableRef table) {
        return "SELECT count(*) AS file_count, "
                + "coalesce(sum(file_size_in_bytes), 0) AS size_bytes, "
                + "coalesce(avg(file_size_in_bytes), 0) AS avg_file_bytes, "
                + "count_if(file_size_in_bytes < " + SMALL_FILE_BYTES + ") AS small_file_count "
                + "FROM " + meta(table, "files");
    }

    public static String allFiles(TableRef table) {
        return "SELECT coalesce(sum(file_size_in_bytes), 0) AS size_bytes FROM " + meta(table, "all_files");
    }

    public static String snapshots(TableRef table) {
        return "SELECT count(*) AS snapshot_count FROM " + meta(table, "snapshots");
    }

    public static String partitions(TableRef table) {
        return "SELECT count(*) AS partition_count FROM " + meta(table, "partitions");
    }

    static String meta(TableRef table, String suffix) {
        return quote(table.schema()) + "." + quote(table.table() + "$" + suffix);
    }

    public static String quote(String ident) {
        return "\"" + ident.replace("\"", "") + "\"";
    }

    public static boolean isIdent(String raw) {
        return raw != null && raw.matches("[A-Za-z_][A-Za-z0-9_]*");
    }
}
