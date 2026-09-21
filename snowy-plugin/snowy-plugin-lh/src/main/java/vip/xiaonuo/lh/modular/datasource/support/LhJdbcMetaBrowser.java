package vip.xiaonuo.lh.modular.datasource.support;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;
import vip.xiaonuo.lh.modular.datasource.enums.LhDatasourceTypeEnum;
import vip.xiaonuo.lh.modular.datasource.form.LhDatasourceConnNormalizer;
import vip.xiaonuo.lh.modular.datasource.result.LhMetaColumnVo;
import vip.xiaonuo.lh.modular.datasource.result.LhMetaObjectVo;

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
 * JDBC {@link DatabaseMetaData} 分层浏览：schema → table/view → column。
 * <p>对齐 SQLREST Manager 元数据树；类型差异与 previewSchema / LhTableColumnFetcher 一致。</p>
 */
@Component
public class LhJdbcMetaBrowser {

    private static final Logger log = LoggerFactory.getLogger(LhJdbcMetaBrowser.class);

    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
            "information_schema", "mysql", "performance_schema", "sys",
            "pg_catalog", "pg_toast");

    @Resource
    private LhVaultClient vaultClient;

    /** @return 非 JDBC 时的降级文案；JDBC 返回 null */
    public String unsupportedMessage(LhDatasource ds) {
        LhDatasourceTypeEnum typeEnum = LhDatasourceTypeEnum.of(ds.getType()).orElse(null);
        if (typeEnum == null || !typeEnum.isJdbc()) {
            return "数据源类型 " + StrUtil.blankToDefault(ds.getType(), "?")
                    + " 不支持 JDBC 元数据浏览";
        }
        return null;
    }

    public boolean supportsJdbc(LhDatasource ds) {
        return unsupportedMessage(ds) == null;
    }

    public List<String> listSchemas(LhDatasource ds) {
        return withConnection(ds, this::doListSchemas);
    }

    public List<LhMetaObjectVo> listTables(LhDatasource ds, String schema) {
        return withConnection(ds, ctx -> doListObjects(ctx, schema, "TABLE"));
    }

    public List<LhMetaObjectVo> listViews(LhDatasource ds, String schema) {
        return withConnection(ds, ctx -> doListObjects(ctx, schema, "VIEW"));
    }

    public List<LhMetaColumnVo> listColumns(LhDatasource ds, String schema, String table) {
        if (StrUtil.isBlank(table)) {
            throw new CommonException("table 不能为空");
        }
        return withConnection(ds, ctx -> doListColumns(ctx, schema, table));
    }

    @FunctionalInterface
    private interface JdbcWork<T> {
        T apply(JdbcCtx ctx) throws Exception;
    }

    private <T> T withConnection(LhDatasource ds, JdbcWork<T> work) {
        if (!supportsJdbc(ds)) {
            throw new CommonException(unsupportedMessage(ds));
        }
        Map<String, Object> secret = vaultClient.readOrEmpty(ds.getVaultPath());
        if (secret == null || secret.isEmpty()) {
            throw new CommonException("Vault 凭证为空，无法浏览元数据");
        }
        String jdbcUrl = first(secret, "jdbcUrl", "url");
        if (StrUtil.isBlank(jdbcUrl)) {
            jdbcUrl = buildJdbcUrl(ds, secret);
        }
        if (StrUtil.isBlank(jdbcUrl)) {
            throw new CommonException("无法解析 JDBC URL");
        }
        String user = first(secret, "username", "user");
        String pwd = first(secret, "password", "passwd");
        LhDatasourceTypeEnum typeEnum = LhDatasourceTypeEnum.of(ds.getType()).orElse(null);
        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, pwd)) {
            return work.apply(new JdbcCtx(conn, ds, secret, typeEnum));
        } catch (CommonException e) {
            throw e;
        } catch (Exception e) {
            log.warn("JDBC meta browse fail dsId={} type={}: {}", ds.getId(), ds.getType(), e.getMessage());
            throw new CommonException("元数据浏览失败: {}", e.getMessage());
        }
    }

    private List<String> doListSchemas(JdbcCtx ctx) throws Exception {
        DatabaseMetaData meta = ctx.conn.getMetaData();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (isMysqlFamily(ctx.typeEnum)) {
            try (ResultSet rs = meta.getCatalogs()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    if (StrUtil.isNotBlank(name) && !SYSTEM_SCHEMAS.contains(name.toLowerCase(Locale.ROOT))) {
                        names.add(name);
                    }
                }
            }
            if (names.isEmpty()) {
                String db = resolveCatalog(ctx);
                if (StrUtil.isNotBlank(db)) {
                    names.add(db);
                }
            }
            return new ArrayList<>(names);
        }
        try (ResultSet rs = meta.getSchemas()) {
            while (rs.next()) {
                String name = rs.getString("TABLE_SCHEM");
                if (StrUtil.isBlank(name)) {
                    continue;
                }
                if (SYSTEM_SCHEMAS.contains(name.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                names.add(name);
            }
        }
        if (names.isEmpty()) {
            try (ResultSet rs = meta.getCatalogs()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    if (StrUtil.isNotBlank(name) && !SYSTEM_SCHEMAS.contains(name.toLowerCase(Locale.ROOT))) {
                        names.add(name);
                    }
                }
            }
        }
        if (names.isEmpty()) {
            String fallback = defaultSchemaHint(ctx);
            if (StrUtil.isNotBlank(fallback)) {
                names.add(fallback);
            }
        }
        return new ArrayList<>(names);
    }

    private List<LhMetaObjectVo> doListObjects(JdbcCtx ctx, String schema, String objectKind) throws Exception {
        String[] types = new String[]{objectKind};
        String catalog;
        String schemaPattern;
        if (isMysqlFamily(ctx.typeEnum)) {
            catalog = StrUtil.blankToDefault(schema, resolveCatalog(ctx));
            schemaPattern = null;
        } else if (isPgFamily(ctx.typeEnum)) {
            catalog = resolveCatalog(ctx);
            schemaPattern = StrUtil.blankToDefault(schema, defaultSchemaHint(ctx));
        } else if (ctx.typeEnum == LhDatasourceTypeEnum.SQLSERVER) {
            catalog = resolveCatalog(ctx);
            schemaPattern = StrUtil.blankToDefault(schema, "dbo");
        } else if (ctx.typeEnum == LhDatasourceTypeEnum.ORACLE) {
            catalog = null;
            schemaPattern = StrUtil.blankToDefault(schema, resolveCatalog(ctx));
            if (StrUtil.isNotBlank(schemaPattern)) {
                schemaPattern = schemaPattern.toUpperCase(Locale.ROOT);
            }
        } else {
            catalog = resolveCatalog(ctx);
            schemaPattern = StrUtil.isBlank(schema) ? null : schema;
        }
        List<LhMetaObjectVo> list = new ArrayList<>();
        DatabaseMetaData meta = ctx.conn.getMetaData();
        try (ResultSet rs = meta.getTables(catalog, schemaPattern, "%", types)) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (StrUtil.isBlank(name)) {
                    continue;
                }
                LhMetaObjectVo vo = new LhMetaObjectVo();
                vo.setName(name);
                vo.setObjectKind("VIEW".equals(objectKind) ? "VIEW" : "TABLE");
                vo.setRemarks(StrUtil.blankToDefault(rs.getString("REMARKS"), ""));
                list.add(vo);
            }
        }
        return list;
    }

    private List<LhMetaColumnVo> doListColumns(JdbcCtx ctx, String schema, String table) throws Exception {
        String tableOnly = bare(table);
        String catalog;
        String schemaPattern;
        if (isMysqlFamily(ctx.typeEnum)) {
            catalog = StrUtil.blankToDefault(schema, resolveCatalog(ctx));
            schemaPattern = null;
        } else if (isPgFamily(ctx.typeEnum)) {
            catalog = resolveCatalog(ctx);
            if (table.contains(".")) {
                String[] parts = table.split("\\.");
                schemaPattern = parts[parts.length - 2];
            } else {
                schemaPattern = StrUtil.blankToDefault(schema, defaultSchemaHint(ctx));
            }
        } else if (ctx.typeEnum == LhDatasourceTypeEnum.SQLSERVER) {
            catalog = resolveCatalog(ctx);
            schemaPattern = StrUtil.blankToDefault(schema, "dbo");
        } else if (ctx.typeEnum == LhDatasourceTypeEnum.ORACLE) {
            catalog = null;
            schemaPattern = StrUtil.blankToDefault(schema, resolveCatalog(ctx));
            if (StrUtil.isNotBlank(schemaPattern)) {
                schemaPattern = schemaPattern.toUpperCase(Locale.ROOT);
            }
            if (StrUtil.isNotBlank(tableOnly)) {
                tableOnly = tableOnly.toUpperCase(Locale.ROOT);
            }
        } else {
            catalog = resolveCatalog(ctx);
            schemaPattern = StrUtil.isBlank(schema) ? null : schema;
        }
        List<LhMetaColumnVo> cols = new ArrayList<>();
        DatabaseMetaData meta = ctx.conn.getMetaData();
        try (ResultSet rs = meta.getColumns(catalog, schemaPattern, tableOnly, "%")) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                if (StrUtil.isBlank(name)) {
                    continue;
                }
                LhMetaColumnVo col = new LhMetaColumnVo();
                col.setName(name);
                String typeName = rs.getString("TYPE_NAME");
                int size = rs.getInt("COLUMN_SIZE");
                int digits = rs.getInt("DECIMAL_DIGITS");
                col.setType(enrichType(typeName, size, digits));
                col.setRemarks(StrUtil.blankToDefault(rs.getString("REMARKS"), ""));
                cols.add(col);
            }
        }
        return cols;
    }

    private static String resolveCatalog(JdbcCtx ctx) {
        return StrUtil.blankToDefault(ctx.ds.getDatabaseName(),
                first(ctx.secret, "database", "db", "catalog"));
    }

    private static String defaultSchemaHint(JdbcCtx ctx) {
        if (isPgFamily(ctx.typeEnum)) {
            return LhDatasourceConnNormalizer.resolveJdbcSchemaName(ctx.secret.get("schema"), "public");
        }
        if (ctx.typeEnum == LhDatasourceTypeEnum.SQLSERVER) {
            return LhDatasourceConnNormalizer.resolveJdbcSchemaName(ctx.secret.get("schema"), "dbo");
        }
        if (ctx.typeEnum == LhDatasourceTypeEnum.ORACLE) {
            return StrUtil.blankToDefault(first(ctx.secret, "schema"), resolveCatalog(ctx));
        }
        return resolveCatalog(ctx);
    }

    private static boolean isMysqlFamily(LhDatasourceTypeEnum t) {
        return t == LhDatasourceTypeEnum.MYSQL || t == LhDatasourceTypeEnum.DORIS
                || t == LhDatasourceTypeEnum.CLICKHOUSE;
    }

    private static boolean isPgFamily(LhDatasourceTypeEnum t) {
        return t == LhDatasourceTypeEnum.PG || t == LhDatasourceTypeEnum.POSTGRESQL;
    }

    private static String enrichType(String typeName, int size, int digits) {
        if (StrUtil.isBlank(typeName)) {
            return "STRING";
        }
        String t = typeName.toUpperCase(Locale.ROOT);
        if ((t.contains("DECIMAL") || t.contains("NUMERIC") || t.equals("NUMBER")) && size > 0) {
            return "DECIMAL(" + size + "," + Math.max(digits, 0) + ")";
        }
        if ((t.contains("VARCHAR") || t.contains("CHAR")) && size > 0 && size < 65535) {
            return t.contains("(") ? typeName : typeName + "(" + size + ")";
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
        String db = StrUtil.blankToDefault(database, "");
        if (type.contains("postgres") || "pg".equals(type)) {
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
        if (type.contains("trino")) {
            return "jdbc:trino://" + host + ":" + StrUtil.blankToDefault(port, "8080") + "/"
                    + StrUtil.blankToDefault(db, "hive");
        }
        return "jdbc:mysql://" + host + ":" + StrUtil.blankToDefault(port, "3306") + "/" + db
                + "?useSSL=false&serverTimezone=Asia/Shanghai";
    }

    private static String bare(String table) {
        if (table == null) {
            return "";
        }
        int dot = table.lastIndexOf('.');
        return dot >= 0 ? table.substring(dot + 1).trim() : table.trim();
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

    private static final class JdbcCtx {
        final Connection conn;
        final LhDatasource ds;
        final Map<String, Object> secret;
        final LhDatasourceTypeEnum typeEnum;

        JdbcCtx(Connection conn, LhDatasource ds, Map<String, Object> secret, LhDatasourceTypeEnum typeEnum) {
            this.conn = conn;
            this.ds = ds;
            this.secret = secret;
            this.typeEnum = typeEnum;
        }
    }
}
