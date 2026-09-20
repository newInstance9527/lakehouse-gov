package vip.xiaonuo.lh.core.engine;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 按数据源方言生成 Flink SQL：CDC / JDBC {@code CREATE TABLE … WITH}。
 * <p>有下游 DAG（{@code _lhLandingTable}）时：有界 JDBC 快照 → JDBC 落 {@code lh_ods_*}，任务可结束；
 * 无下游的纯 CDC 仍为流式 {@code SELECT}（不会自动串 Spark/DataX）。</p>
 */
public final class FlinkSourceSqlCompiler {

    private FlinkSourceSqlCompiler() {
    }

    public record Column(String name, String flinkType, boolean primaryKey) {
        public Column(String name, String flinkType) {
            this(name, flinkType, false);
        }
    }

    public static String compile(IgEtlNodeLike n, JSONObject conf, List<Column> columns) {
        String table = firstNonBlank(
                conf.getStr("table"), conf.getStr("src"), conf.getStr("objectName"), firstTable(conf));
        if (StrUtil.isBlank(table)) {
            return "SELECT 1 AS _lh_source_probe";
        }
        String dbType = StrUtil.blankToDefault(firstNonBlank(
                conf.getStr("dbType"), conf.getStr("lhDsType"), conf.getStr("dsType"), conf.getStr("type")), "");
        String database = firstNonBlank(conf.getStr("database"), conf.getStr("schema"), conf.getStr("lhDatabase"));
        String host = firstNonBlank(conf.getStr("host"), conf.getStr("lhHost"), conf.getStr("endpointHost"));
        String port = portOf(conf);
        String tableOnly = bareTable(table);
        String schema = firstNonBlank(conf.getStr("pgSchema"), conf.getStr("schemaName"),
                isPg(dbType) ? "public" : null);
        boolean cdc = isCdc(conf);
        Dialect d = Dialect.of(dbType);
        boolean boundedChain = conf.getBool("_lhBoundedChain", false)
                || StrUtil.isNotBlank(conf.getStr("_lhLandingTable"));
        // DAG 串联必须有界：用 JDBC 快照替代 CDC 流（否则 INSERT 永不结束，下游 Spark/DataX 永不调度）
        boolean useCdc = cdc && d.supportsCdc() && !boundedChain;

        List<Column> cols = columns == null ? List.of() : columns;
        if (cols.isEmpty()) {
            cols = columnsFromConf(conf);
        }
        if (cols.isEmpty()) {
            String pk = firstNonBlank(conf.getStr("pk"), "id");
            for (String p : pk.split("[,;\\s]+")) {
                if (StrUtil.isNotBlank(p)) {
                    cols = new ArrayList<>(cols);
                    cols.add(new Column(p.trim(), "STRING", true));
                }
            }
        }
        if (cols.isEmpty()) {
            cols = List.of(new Column("id", "STRING", true));
        }

        String alias = "_lh_src_" + safeIdent(tableOnly);
        String h = StrUtil.blankToDefault(host, "${LH_HOST}");
        String p = StrUtil.blankToDefault(port, d.defaultPort());
        String db = StrUtil.blankToDefault(database, "${LH_DATABASE}");
        String tz = firstNonBlank(conf.getStr("serverTimeZone"), "Asia/Shanghai");
        String startup = firstNonBlank(conf.getStr("startupMode"), useCdc ? "latest-offset" : "initial");
        String nodeKey = n == null ? "src" : StrUtil.blankToDefault(n.nodeKey(), "src");
        String landing = firstNonBlank(conf.getStr("_lhLandingTable"), conf.getStr("landingTable"));
        String landBare = StrUtil.isBlank(landing) ? null : LhStagingTables.bareTable(landing);

        StringBuilder sb = new StringBuilder();
        if (boundedChain) {
            sb.append("SET 'execution.runtime-mode' = 'BATCH';\n");
        }
        sb.append("-- source ").append(nodeKey).append(' ').append(d.label()).append(' ')
                .append(db).append('.').append(tableOnly)
                .append(" via ").append(useCdc ? d.cdcConnector() : "jdbc");
        if (StrUtil.isNotBlank(landBare)) {
            sb.append(" → land ").append(db).append('.').append(landBare);
        }
        sb.append('\n');
        sb.append("-- credentials: LH_JDBC_USER / LH_JDBC_PASSWORD (Vault)\n");
        if (boundedChain && cdc) {
            sb.append("-- note: DAG 有下游，CDC 改为 JDBC 有界快照以便任务结束并落 lh_ods_*\n");
        }
        sb.append("CREATE TABLE IF NOT EXISTS `").append(alias).append("` (\n");
        List<Column> usable = new ArrayList<>();
        for (Column c : cols) {
            if (c != null && StrUtil.isNotBlank(c.name())) {
                usable.add(c);
            }
        }
        Set<String> pkNames = new LinkedHashSet<>();
        for (Column c : usable) {
            if (c.primaryKey()) {
                pkNames.add(c.name().replace("`", ""));
            }
        }
        if (pkNames.isEmpty() && StrUtil.isNotBlank(conf.getStr("pk"))) {
            for (String pkn : conf.getStr("pk").split("[,;\\s]+")) {
                if (StrUtil.isNotBlank(pkn)) {
                    pkNames.add(pkn.trim().replace("`", ""));
                }
            }
        }
        appendColDefs(sb, usable, pkNames);
        sb.append(") WITH (\n");
        if (useCdc) {
            appendCdcOptions(sb, d, h, p, db, schema, tableOnly, tz, startup);
        } else {
            appendJdbcOptions(sb, d, h, p, db, schema, tableOnly, tz);
        }
        sb.append(");\n");

        if (StrUtil.isNotBlank(landBare)) {
            String landAlias = "_lh_land_" + safeIdent(landBare);
            sb.append("CREATE TABLE IF NOT EXISTS `").append(landAlias).append("` (\n");
            appendColDefs(sb, usable, pkNames);
            sb.append(") WITH (\n");
            appendJdbcOptions(sb, d, h, p, db, schema, landBare, tz);
            sb.append(");\n");
            sb.append("INSERT INTO `").append(landAlias).append("` SELECT * FROM `").append(alias).append("`;\n");
        } else {
            sb.append("SELECT * FROM `").append(alias).append("`");
        }
        return sb.toString();
    }

    private static void appendColDefs(StringBuilder sb, List<Column> usable, Set<String> pkNames) {
        for (int i = 0; i < usable.size(); i++) {
            Column c = usable.get(i);
            sb.append("  `").append(c.name().replace("`", "")).append("` ")
                    .append(StrUtil.blankToDefault(c.flinkType(), "STRING"));
            boolean more = i < usable.size() - 1 || !pkNames.isEmpty();
            if (more) {
                sb.append(',');
            }
            sb.append('\n');
        }
        if (!pkNames.isEmpty()) {
            sb.append("  PRIMARY KEY (");
            int i = 0;
            for (String pkn : pkNames) {
                if (i++ > 0) {
                    sb.append(", ");
                }
                sb.append('`').append(pkn).append('`');
            }
            sb.append(") NOT ENFORCED\n");
        }
    }

    private static void appendCdcOptions(
            StringBuilder sb, Dialect d, String host, String port, String database,
            String schema, String table, String tz, String startup) {
        sb.append("  'connector' = '").append(d.cdcConnector()).append("',\n");
        sb.append("  'hostname' = '").append(esc(host)).append("',\n");
        sb.append("  'port' = '").append(esc(port)).append("',\n");
        sb.append("  'username' = '${LH_JDBC_USER}',\n");
        sb.append("  'password' = '${LH_JDBC_PASSWORD}',\n");
        switch (d) {
            case MYSQL -> {
                sb.append("  'database-name' = '").append(esc(database)).append("',\n");
                sb.append("  'table-name' = '").append(esc(table)).append("',\n");
                sb.append("  'server-time-zone' = '").append(esc(tz)).append("',\n");
                sb.append("  'scan.startup.mode' = '").append(esc(startup)).append("'\n");
            }
            case POSTGRES -> {
                sb.append("  'database-name' = '").append(esc(database)).append("',\n");
                sb.append("  'schema-name' = '").append(esc(StrUtil.blankToDefault(schema, "public"))).append("',\n");
                sb.append("  'table-name' = '").append(esc(table)).append("',\n");
                sb.append("  'slot.name' = 'lh_").append(safeIdent(table)).append("',\n");
                sb.append("  'decoding.plugin.name' = 'pgoutput',\n");
                sb.append("  'scan.startup.mode' = '").append(esc(startup)).append("'\n");
            }
            case ORACLE -> {
                sb.append("  'database-name' = '").append(esc(database)).append("',\n");
                sb.append("  'schema-name' = '").append(esc(StrUtil.blankToDefault(schema, database))).append("',\n");
                sb.append("  'table-name' = '").append(esc(table)).append("',\n");
                sb.append("  'scan.startup.mode' = '").append(esc(startup)).append("'\n");
            }
            case SQLSERVER -> {
                sb.append("  'database-name' = '").append(esc(database)).append("',\n");
                sb.append("  'schema-name' = '").append(esc(StrUtil.blankToDefault(schema, "dbo"))).append("',\n");
                sb.append("  'table-name' = '").append(esc(table)).append("',\n");
                sb.append("  'scan.startup.mode' = '").append(esc(startup)).append("'\n");
            }
            default -> {
                sb.append("  'database-name' = '").append(esc(database)).append("',\n");
                sb.append("  'table-name' = '").append(esc(table)).append("'\n");
            }
        }
    }

    private static void appendJdbcOptions(
            StringBuilder sb, Dialect d, String host, String port, String database,
            String schema, String table, String tz) {
        String url = d.jdbcUrl(host, port, database, tz);
        sb.append("  'connector' = 'jdbc',\n");
        sb.append("  'url' = '").append(esc(url)).append("',\n");
        String tableName = table;
        if (d == Dialect.POSTGRES || d == Dialect.ORACLE || d == Dialect.SQLSERVER) {
            String sch = StrUtil.blankToDefault(schema, d == Dialect.SQLSERVER ? "dbo" : d == Dialect.POSTGRES ? "public" : database);
            tableName = sch + "." + table;
        }
        sb.append("  'table-name' = '").append(esc(tableName)).append("',\n");
        sb.append("  'username' = '${LH_JDBC_USER}',\n");
        sb.append("  'password' = '${LH_JDBC_PASSWORD}'\n");
    }

    /** 从 conf.fieldRules / columns 抽取列 */
    public static List<Column> columnsFromConf(JSONObject conf) {
        List<Column> out = new ArrayList<>();
        if (conf == null) {
            return out;
        }
        JSONArray rules = conf.getJSONArray("fieldRules");
        if (rules != null) {
            for (int i = 0; i < rules.size(); i++) {
                JSONObject r = rules.getJSONObject(i);
                if (r == null) {
                    continue;
                }
                String field = StrUtil.trim(r.getStr("field"));
                if (StrUtil.isBlank(field)) {
                    continue;
                }
                String cast = firstNonBlank(r.getStr("castType"), r.getStr("type"), "STRING");
                out.add(new Column(field, toFlinkType(cast), false));
            }
        }
        JSONArray cols = conf.getJSONArray("_lhColumns");
        if (cols != null && out.isEmpty()) {
            for (int i = 0; i < cols.size(); i++) {
                JSONObject c = cols.getJSONObject(i);
                if (c == null) {
                    continue;
                }
                String name = firstNonBlank(c.getStr("name"), c.getStr("column"), c.getStr("field"));
                if (StrUtil.isBlank(name)) {
                    continue;
                }
                out.add(new Column(name, toFlinkType(firstNonBlank(c.getStr("flinkType"), c.getStr("type"))),
                        c.getBool("pk", false) || c.getBool("primaryKey", false)));
            }
        }
        // conf.pk 标记主键
        if (!out.isEmpty() && StrUtil.isNotBlank(conf.getStr("pk"))) {
            Set<String> pks = new LinkedHashSet<>();
            for (String p : conf.getStr("pk").split("[,;\\s]+")) {
                if (StrUtil.isNotBlank(p)) {
                    pks.add(p.trim().toLowerCase(Locale.ROOT));
                }
            }
            List<Column> marked = new ArrayList<>();
            for (Column c : out) {
                marked.add(new Column(c.name(), c.flinkType(), pks.contains(c.name().toLowerCase(Locale.ROOT))));
            }
            return marked;
        }
        return out;
    }

    /**
     * 把 {@code _lhColumnObjs} / 任意 List/JSONArray 元素收成 {@link Column}。
     * Hutool {@code JSONObject#set} 后取出时 record 常变成 {@code JSONObject}，不可直接强转。
     */
    public static List<Column> coerceColumns(Object raw) {
        List<Column> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        Iterable<?> it;
        if (raw instanceof JSONArray ja) {
            it = ja;
        } else if (raw instanceof List<?> list) {
            it = list;
        } else if (raw instanceof Column c) {
            out.add(c);
            return out;
        } else {
            return out;
        }
        for (Object o : it) {
            Column c = coerceOneColumn(o);
            if (c != null) {
                out.add(c);
            }
        }
        return out;
    }

    private static Column coerceOneColumn(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Column c) {
            return c;
        }
        if (o instanceof JSONObject jo) {
            String name = firstNonBlank(jo.getStr("name"), jo.getStr("column"), jo.getStr("field"));
            if (StrUtil.isBlank(name)) {
                return null;
            }
            return new Column(name,
                    toFlinkType(firstNonBlank(jo.getStr("flinkType"), jo.getStr("type"))),
                    jo.getBool("pk", false) || jo.getBool("primaryKey", false));
        }
        if (o instanceof Map<?, ?> m) {
            Object nameObj = firstNonNull(m.get("name"), m.get("column"), m.get("field"));
            if (nameObj == null || StrUtil.isBlank(String.valueOf(nameObj))) {
                return null;
            }
            Object typeObj = firstNonNull(m.get("flinkType"), m.get("type"));
            boolean pk = Boolean.TRUE.equals(m.get("pk")) || Boolean.TRUE.equals(m.get("primaryKey"));
            return new Column(String.valueOf(nameObj).trim(),
                    toFlinkType(typeObj == null ? null : String.valueOf(typeObj)), pk);
        }
        return null;
    }

    private static Object firstNonNull(Object... vals) {
        if (vals == null) {
            return null;
        }
        for (Object v : vals) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    public static String toFlinkType(String raw) {
        if (StrUtil.isBlank(raw)) {
            return "STRING";
        }
        String t = raw.trim().toUpperCase(Locale.ROOT);
        String base = t.replaceAll("\\(.*\\)", "").trim();
        return switch (base) {
            case "INT", "INTEGER", "SMALLINT", "TINYINT", "MEDIUMINT", "INT2", "INT4", "SERIAL" -> "INT";
            case "BIGINT", "INT8", "BIGSERIAL" -> "BIGINT";
            case "FLOAT", "REAL", "FLOAT4" -> "FLOAT";
            case "DOUBLE", "DOUBLE PRECISION", "FLOAT8" -> "DOUBLE";
            case "DECIMAL", "NUMERIC", "NUMBER" -> t.contains("(") ? "DECIMAL" + t.substring(t.indexOf('(')) : "DECIMAL(38,10)";
            case "BOOLEAN", "BOOL", "BIT" -> "BOOLEAN";
            case "DATE" -> "DATE";
            case "TIME" -> "TIME";
            case "TIMESTAMP", "DATETIME", "DATETIME2", "TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE" -> "TIMESTAMP(3)";
            case "BINARY", "VARBINARY", "BLOB", "BYTEA", "RAW" -> "BYTES";
            default -> "STRING";
        };
    }

    /** 最小节点接口，避免编译器依赖实体 */
    public interface IgEtlNodeLike {
        String nodeKey();
    }

    public static IgEtlNodeLike wrap(vip.xiaonuo.lh.modular.etl.entity.IgEtlNode n) {
        return () -> n == null ? "src" : n.getNodeKey();
    }

    private enum Dialect {
        MYSQL, POSTGRES, ORACLE, SQLSERVER, CLICKHOUSE, GENERIC;

        static Dialect of(String dbType) {
            String t = StrUtil.blankToDefault(dbType, "").toLowerCase(Locale.ROOT);
            if (t.contains("mysql") || t.contains("mariadb") || t.contains("tidb") || t.contains("doris")) {
                return MYSQL;
            }
            if (t.contains("postgres") || t.contains("pg") || t.contains("greenplum")) {
                return POSTGRES;
            }
            if (t.contains("oracle")) {
                return ORACLE;
            }
            if (t.contains("sqlserver") || t.contains("mssql")) {
                return SQLSERVER;
            }
            if (t.contains("clickhouse") || t.contains("ck")) {
                return CLICKHOUSE;
            }
            return GENERIC;
        }

        String label() {
            return name();
        }

        boolean supportsCdc() {
            return this == MYSQL || this == POSTGRES || this == ORACLE || this == SQLSERVER;
        }

        String cdcConnector() {
            return switch (this) {
                case MYSQL -> "mysql-cdc";
                case POSTGRES -> "postgres-cdc";
                case ORACLE -> "oracle-cdc";
                case SQLSERVER -> "sqlserver-cdc";
                default -> "jdbc";
            };
        }

        String defaultPort() {
            return switch (this) {
                case MYSQL, CLICKHOUSE -> "3306";
                case POSTGRES -> "5432";
                case ORACLE -> "1521";
                case SQLSERVER -> "1433";
                default -> "3306";
            };
        }

        String jdbcUrl(String host, String port, String database, String tz) {
            return switch (this) {
                case MYSQL -> "jdbc:mysql://" + host + ":" + port + "/" + database
                        + "?useSSL=false&serverTimezone=" + tz;
                case POSTGRES -> "jdbc:postgresql://" + host + ":" + port + "/" + database;
                case ORACLE -> "jdbc:oracle:thin:@//" + host + ":" + port + "/" + database;
                case SQLSERVER -> "jdbc:sqlserver://" + host + ":" + port + ";databaseName=" + database;
                case CLICKHOUSE -> "jdbc:clickhouse://" + host + ":" + port + "/" + database;
                default -> "jdbc:mysql://" + host + ":" + port + "/" + database;
            };
        }
    }

    private static boolean isCdc(JSONObject conf) {
        if ("cdc".equalsIgnoreCase(conf.getStr("mode")) || "cdc".equalsIgnoreCase(conf.getStr("source_mode"))) {
            return true;
        }
        String cdcEngine = StrUtil.blankToDefault(conf.getStr("cdcEngine"), conf.getStr("cdc_engine"));
        return StrUtil.isNotBlank(cdcEngine) && cdcEngine.toLowerCase(Locale.ROOT).contains("flink");
    }

    private static boolean isPg(String dbType) {
        String t = StrUtil.blankToDefault(dbType, "").toLowerCase(Locale.ROOT);
        return t.contains("postgres") || t.contains("pg");
    }

    private static String portOf(JSONObject conf) {
        Object portObj = conf.get("port");
        if (portObj == null) {
            portObj = conf.get("lhPort");
        }
        if (portObj == null) {
            portObj = conf.get("endpointPort");
        }
        if (portObj == null) {
            return null;
        }
        String p = String.valueOf(portObj).trim();
        return "null".equalsIgnoreCase(p) || StrUtil.isBlank(p) ? null : p;
    }

    private static String firstTable(JSONObject conf) {
        Object tables = conf.get("tables");
        if (tables instanceof Iterable<?> it) {
            for (Object o : it) {
                if (o != null && StrUtil.isNotBlank(String.valueOf(o))) {
                    return String.valueOf(o).trim();
                }
            }
        }
        return null;
    }

    private static String bareTable(String table) {
        if (StrUtil.isBlank(table)) {
            return table;
        }
        int dot = table.lastIndexOf('.');
        return dot >= 0 ? table.substring(dot + 1) : table.trim();
    }

    private static String safeIdent(String s) {
        return StrUtil.blankToDefault(s, "x").replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private static String esc(String s) {
        return StrUtil.blankToDefault(s, "").replace("'", "''");
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (StrUtil.isNotBlank(v)) {
                return v.trim();
            }
        }
        return null;
    }
}
