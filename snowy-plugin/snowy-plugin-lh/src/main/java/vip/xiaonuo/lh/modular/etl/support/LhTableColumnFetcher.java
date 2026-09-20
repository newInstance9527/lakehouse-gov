package vip.xiaonuo.lh.modular.etl.support;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.core.engine.FlinkSourceSqlCompiler;
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;
import vip.xiaonuo.lh.modular.datasource.mapper.LhDatasourceMapper;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 发布时按 dsId + 表名拉取 JDBC 列元数据，供 Flink CDC/JDBC CREATE TABLE 使用。
 * <p>失败 soft-fail，由调用方回退 fieldRules / 主键骨架。</p>
 */
@Component
public class LhTableColumnFetcher {

    private static final Logger log = LoggerFactory.getLogger(LhTableColumnFetcher.class);

    @Resource
    private LhDatasourceMapper datasourceMapper;
    @Resource
    private LhVaultClient vaultClient;

    public List<FlinkSourceSqlCompiler.Column> fetch(String dsId, String table, String pkCsv) {
        if (StrUtil.isBlank(dsId) || StrUtil.isBlank(table)) {
            return List.of();
        }
        LhDatasource ds = datasourceMapper.selectById(dsId);
        if (ds == null || StrUtil.isBlank(ds.getVaultPath())) {
            return List.of();
        }
        Map<String, Object> secret = vaultClient.readOrEmpty(ds.getVaultPath());
        if (secret == null || secret.isEmpty()) {
            return List.of();
        }
        String jdbcUrl = first(secret, "jdbcUrl", "url");
        String user = first(secret, "username", "user");
        String pwd = first(secret, "password", "passwd");
        if (StrUtil.isBlank(jdbcUrl)) {
            jdbcUrl = buildJdbcUrl(ds, secret);
        }
        if (StrUtil.isBlank(jdbcUrl)) {
            return List.of();
        }
        String tableOnly = bare(table);
        String schemaHint = schemaOf(table, ds, secret);
        String catalog = catalogOf(ds, secret);
        Set<String> pks = parsePk(pkCsv);
        List<FlinkSourceSqlCompiler.Column> cols = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, pwd)) {
            DatabaseMetaData meta = conn.getMetaData();
            // 主键
            try (ResultSet pkRs = meta.getPrimaryKeys(catalog, schemaHint, tableOnly)) {
                while (pkRs.next()) {
                    String col = pkRs.getString("COLUMN_NAME");
                    if (StrUtil.isNotBlank(col)) {
                        pks.add(col.toLowerCase(Locale.ROOT));
                    }
                }
            } catch (Exception ignored) {
                // soft
            }
            try (ResultSet rs = meta.getColumns(catalog, schemaHint, tableOnly, "%")) {
                while (rs.next()) {
                    String name = rs.getString("COLUMN_NAME");
                    if (StrUtil.isBlank(name)) {
                        continue;
                    }
                    String typeName = rs.getString("TYPE_NAME");
                    int size = rs.getInt("COLUMN_SIZE");
                    int digits = rs.getInt("DECIMAL_DIGITS");
                    String flink = FlinkSourceSqlCompiler.toFlinkType(enrichType(typeName, size, digits));
                    boolean isPk = pks.contains(name.toLowerCase(Locale.ROOT));
                    cols.add(new FlinkSourceSqlCompiler.Column(name, flink, isPk));
                }
            }
        } catch (Exception e) {
            log.warn("fetch columns soft-fail dsId={} table={}: {}", dsId, table, e.getMessage());
            return List.of();
        }
        return cols;
    }

    private static String enrichType(String typeName, int size, int digits) {
        if (StrUtil.isBlank(typeName)) {
            return "STRING";
        }
        String t = typeName.toUpperCase(Locale.ROOT);
        if ((t.contains("DECIMAL") || t.contains("NUMERIC") || t.equals("NUMBER")) && size > 0) {
            int d = Math.max(digits, 0);
            return "DECIMAL(" + size + "," + d + ")";
        }
        if ((t.contains("VARCHAR") || t.contains("CHAR")) && size > 0 && size < 65535) {
            return t + "(" + size + ")";
        }
        return typeName;
    }

    private static String buildJdbcUrl(LhDatasource ds, Map<String, Object> secret) {
        String host = StrUtil.blankToDefault(ds.getEndpointHost(), first(secret, "host", "hostname"));
        String port = StrUtil.blankToDefault(ds.getEndpointPort(), first(secret, "port"));
        String database = StrUtil.blankToDefault(ds.getDatabaseName(), first(secret, "database", "db"));
        if (StrUtil.isBlank(host)) {
            return null;
        }
        String type = StrUtil.blankToDefault(ds.getType(), "").toLowerCase(Locale.ROOT);
        String p = StrUtil.blankToDefault(port, "3306");
        String db = StrUtil.blankToDefault(database, "");
        if (type.contains("postgres") || type.contains("pg")) {
            return "jdbc:postgresql://" + host + ":" + StrUtil.blankToDefault(port, "5432") + "/" + db;
        }
        if (type.contains("oracle")) {
            return "jdbc:oracle:thin:@//" + host + ":" + StrUtil.blankToDefault(port, "1521") + "/" + db;
        }
        if (type.contains("sqlserver") || type.contains("mssql")) {
            return "jdbc:sqlserver://" + host + ":" + StrUtil.blankToDefault(port, "1433") + ";databaseName=" + db;
        }
        if (type.contains("clickhouse") || type.contains("ck")) {
            return "jdbc:clickhouse://" + host + ":" + StrUtil.blankToDefault(port, "8123") + "/" + db;
        }
        return "jdbc:mysql://" + host + ":" + p + "/" + db + "?useSSL=false&serverTimezone=Asia/Shanghai";
    }

    private static String catalogOf(LhDatasource ds, Map<String, Object> secret) {
        String type = StrUtil.blankToDefault(ds.getType(), "").toLowerCase(Locale.ROOT);
        if (type.contains("mysql") || type.contains("mariadb") || type.contains("tidb") || type.contains("doris")) {
            return StrUtil.blankToDefault(ds.getDatabaseName(), first(secret, "database"));
        }
        return StrUtil.blankToDefault(ds.getDatabaseName(), first(secret, "database"));
    }

    private static String schemaOf(String table, LhDatasource ds, Map<String, Object> secret) {
        if (table != null && table.contains(".")) {
            String[] p = table.split("\\.");
            if (p.length >= 2) {
                return p[p.length - 2];
            }
        }
        String type = StrUtil.blankToDefault(ds.getType(), "").toLowerCase(Locale.ROOT);
        if (type.contains("postgres") || type.contains("pg")) {
            return StrUtil.blankToDefault(first(secret, "schema"), "public");
        }
        if (type.contains("sqlserver") || type.contains("mssql")) {
            return StrUtil.blankToDefault(first(secret, "schema"), "dbo");
        }
        if (type.contains("oracle")) {
            return StrUtil.blankToDefault(first(secret, "schema"), ds.getDatabaseName());
        }
        return null;
    }

    private static Set<String> parsePk(String pkCsv) {
        Set<String> pks = new LinkedHashSet<>();
        if (StrUtil.isBlank(pkCsv)) {
            return pks;
        }
        for (String p : pkCsv.split("[,;\\s]+")) {
            if (StrUtil.isNotBlank(p)) {
                pks.add(p.trim().toLowerCase(Locale.ROOT));
            }
        }
        return pks;
    }

    private static String bare(String table) {
        int dot = table.lastIndexOf('.');
        return dot >= 0 ? table.substring(dot + 1) : table.trim();
    }

    private static String first(Map<String, Object> m, String... keys) {
        if (m == null) {
            return null;
        }
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null && StrUtil.isNotBlank(String.valueOf(v))) {
                return String.valueOf(v).trim();
            }
        }
        return null;
    }
}
