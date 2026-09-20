package vip.xiaonuo.lh.modular.etl.support;

import cn.hutool.core.util.StrUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vip.xiaonuo.lh.core.engine.FlinkSourceSqlCompiler;
import vip.xiaonuo.lh.core.engine.LhStagingTables;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

/**
 * 发布/试跑前在源库创建 {@code lh_ods_*} / {@code lh_clean_*}（IF NOT EXISTS），供 Flink→Spark→DataX 落表串联。
 */
public final class LhStagingDdlEnsured {

    private static final Logger log = LoggerFactory.getLogger(LhStagingDdlEnsured.class);

    private LhStagingDdlEnsured() {
    }

    public static void ensureMysqlFamily(
            LhDatasource ds, String jdbcUrl, String user, String password,
            String database, String tableOnly, List<?> colsRaw) {
        List<FlinkSourceSqlCompiler.Column> cols = FlinkSourceSqlCompiler.coerceColumns(colsRaw);
        if (ds == null || StrUtil.isBlank(jdbcUrl) || cols.isEmpty()) {
            return;
        }
        String type = StrUtil.blankToDefault(ds.getType(), "").toLowerCase();
        if (!(type.contains("mysql") || type.contains("mariadb") || type.contains("tidb"))) {
            log.debug("staging DDL skip non-mysql type={}", type);
            return;
        }
        String ods = LhStagingTables.odsTable(tableOnly);
        String clean = LhStagingTables.cleanTable(tableOnly);
        String ddlOds = mysqlCreate(database, ods, cols);
        String ddlClean = mysqlCreate(database, clean, cols);
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            try {
                Class.forName("com.mysql.jdbc.Driver");
            } catch (ClassNotFoundException ignored) {
                log.warn("staging DDL: no mysql driver on classpath");
                return;
            }
        }
        try (Connection c = DriverManager.getConnection(jdbcUrl, user, password);
             Statement st = c.createStatement()) {
            st.execute(ddlOds);
            st.execute(ddlClean);
            log.info("staging DDL ok db={} ods={} clean={}", database, ods, clean);
        } catch (Exception e) {
            log.warn("staging DDL soft-fail: {}", e.getMessage());
        }
    }

    static String mysqlCreate(String database, String table, List<FlinkSourceSqlCompiler.Column> cols) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS ");
        if (StrUtil.isNotBlank(database)) {
            sb.append('`').append(database.replace("`", "")).append("`.`");
        } else {
            sb.append('`');
        }
        sb.append(table.replace("`", "")).append("` (\n");
        for (int i = 0; i < cols.size(); i++) {
            FlinkSourceSqlCompiler.Column col = cols.get(i);
            sb.append("  `").append(col.name().replace("`", "")).append("` ")
                    .append(toMysqlType(col.flinkType()));
            if (i < cols.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append(")");
        return sb.toString();
    }

    private static String toMysqlType(String flinkType) {
        String t = StrUtil.blankToDefault(flinkType, "STRING").toUpperCase();
        if (t.startsWith("TIMESTAMP")) {
            return "DATETIME(3) NULL";
        }
        if (t.startsWith("DECIMAL") || t.startsWith("NUMERIC")) {
            return "DECIMAL(38,10) NULL";
        }
        return switch (t) {
            case "INT", "INTEGER" -> "INT NULL";
            case "BIGINT" -> "BIGINT NULL";
            case "DOUBLE", "FLOAT" -> "DOUBLE NULL";
            case "BOOLEAN" -> "TINYINT(1) NULL";
            case "DATE" -> "DATE NULL";
            default -> "TEXT NULL";
        };
    }
}
