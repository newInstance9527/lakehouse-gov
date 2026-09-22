package vip.xiaonuo.lh.core.engine;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将门户节点 conf 投影为 DS 可执行 SQL / Shell / DataX / Flink / Spark（步骤 2–3）。
 * <p>优先使用节点 conf.sql；否则按 source/sink 表名拼 SELECT/INSERT 占位。</p>
 */
public final class DsTaskScriptBuilder {

    private DsTaskScriptBuilder() {
    }

    public static Map<String, Object> buildTaskParams(IgEtlNode n, String engine, String dsTaskType) {
        return buildTaskParams(n, engine, dsTaskType, null);
    }

    public static Map<String, Object> buildTaskParams(
            IgEtlNode n, String engine, String dsTaskType, String upstreamTable) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("localParams", java.util.List.of());
        params.put("resourceList", java.util.List.of());
        params.put("lhNodeKey", n.getNodeKey());
        params.put("lhNodeType", n.getNodeType());
        params.put("lhEngine", engine);

        JSONObject conf = parseConf(n.getConfJson());
        if (StrUtil.isNotBlank(upstreamTable) && StrUtil.isBlank(conf.getStr("_lhUpstreamTable"))) {
            conf.set("_lhUpstreamTable", upstreamTable);
        }
        if (StrUtil.isNotBlank(engine)) {
            conf.set("_lhEngine", engine);
        }
        String sql = resolveSql(n, conf);
        params.put("lhSql", sql);

        if ("SQL".equals(dsTaskType)) {
            params.put("type", mapDsSqlType(conf));
            Object dsId = conf.get("dsDatasourceId");
            if (dsId == null) {
                dsId = conf.get("datasourceId");
            }
            params.put("datasource", dsId);
            params.put("sql", sql);
            params.put("sqlType", isQuery(sql) ? "0" : "1");
            params.put("preStatements", "");
            params.put("postStatements", "");
            params.put("segmentSeparator", "");
            params.put("displayRows", 10);
            // 无 DS 内置 datasource 时 Worker 可降级用 Shell+Trino
            params.put("rawScript", trinoShellFallback(n, sql));
        } else if ("SWITCH".equals(dsTaskType)) {
            params.put("switchResult", Map.of("dependTaskList", java.util.List.of(), "nextNode", java.util.List.of()));
        } else if ("FLINK".equals(dsTaskType) || "FLINK_STREAM".equals(dsTaskType) || "flink".equalsIgnoreCase(engine)) {
            // DS 原生 FLINK：有 jar → JAVA；无 jar → programType=SQL + rawScript=Flink SQL
            Integer jarResId = conf.getInt("dsResourceId", conf.getInt("mainJarId", 0));
            if (jarResId != null && jarResId > 0) {
                params.put("programType", "JAVA");
                params.put("mainJar", Map.of("id", jarResId));
                params.put("mainClass", firstNonBlank(conf.getStr("mainClass"), conf.getStr("entryClass"), ""));
                params.put("mainArgs", StrUtil.blankToDefault(conf.getStr("programArgs"), ""));
                params.put("rawScript", "");
            } else {
                params.put("programType", "SQL");
                params.put("rawScript", sql.endsWith(";") ? sql : sql + ";");
                params.put("initScript", StrUtil.blankToDefault(conf.getStr("initScript"), ""));
            }
            // local → Worker 内嵌，Flink Web UI 无作业；standalone → 提交到 lh-flink-jm
            params.put("deployMode", StrUtil.blankToDefault(conf.getStr("deployMode"), "standalone"));
            params.put("flinkVersion", ">=1.13");
            params.put("parallelism", conf.getInt("parallelism", 1));
            params.put("jobManagerMemory", StrUtil.blankToDefault(conf.getStr("jobManagerMemory"), "1G"));
            // standalone 远程提交吃集群 flink-conf；此字段主要给 YARN/K8s。默认抬到 4G，避免 Iceberg upsert OOM。
            params.put("taskManagerMemory", StrUtil.blankToDefault(conf.getStr("taskManagerMemory"), "4G"));
            params.put("slot", conf.getInt("slot", 1));
            params.put("taskManager", conf.getInt("taskManager", 2));
            params.put("appName", "lh_" + safeIdent(n.getNodeKey()));
            params.put("lhFlinkJarId", conf.getStr("jarId"));
            if (StrUtil.isBlank(conf.getStr("initScript"))) {
                // 跨机 Flink：rest.address 由发布侧 DsClient 按 lh.flink.url/restAddress 覆盖；此处给本地默认
                String restHost = firstNonBlank(conf.getStr("flinkRestAddress"), conf.getStr("restAddress"), "10.0.0.181");
                String restPort = firstNonBlank(conf.getStr("flinkRestPort"), conf.getStr("restPort"), "8081");
                params.put("initScript",
                        "SET 'execution.target' = 'remote';\n"
                                + "SET 'rest.address' = '" + restHost + "';\n"
                                + "SET 'rest.port' = '" + restPort + "';\n"
                                + "SET 'execution.attached' = 'true';\n"
                                + "SET 'execution.shutdown-on-attached-exit' = 'true';\n");
            }
            // 运维对照：REST/docker 提交脚本（不作为 FLINK 任务执行体）
            params.put("lhFlinkRestScript", FlinkSubmitBuilder.buildShell(n, sql, conf));
        } else if ("SPARK".equals(dsTaskType) || "spark".equalsIgnoreCase(engine)) {
            Integer jarResId = conf.getInt("dsResourceId", conf.getInt("mainJarId", 0));
            if (jarResId != null && jarResId > 0) {
                params.put("programType", "JAVA");
                params.put("mainJar", Map.of("id", jarResId));
                params.put("mainClass", firstNonBlank(conf.getStr("mainClass"), conf.getStr("entryClass"), ""));
                params.put("rawScript", "");
            } else {
                // 无 jar：调用方应改 taskType=SHELL；此处同时给出可执行 shell，避免误投 SPARK
                params.put("programType", "SQL");
                params.put("sqlExecutionType", "SCRIPT");
                params.put("lhSparkSql", sql);
                params.put("lhPreferShell", true);
                params.put("rawScript", SparkSubmitBuilder.buildShell(n, sql, conf));
            }
            params.put("deployMode", StrUtil.blankToDefault(conf.getStr("deployMode"), "local"));
            params.put("master", firstNonBlank(conf.getStr("master"), conf.getStr("sparkMaster"), "local[*]"));
            params.put("mainClass", firstNonBlank(conf.getStr("mainClass"), conf.getStr("entryClass")));
            // Spark JDBC：只挂一份驱动；同名 jar 多路径会 IllegalArgumentException
            // deployMode 必须为 local（勿用 client+local[*]，否则 DS 按 YARN 容错反复重派，下游永不调度）
            if (StrUtil.isBlank(conf.getStr("others"))) {
                params.put("others", "--jars /opt/spark/jars/mysql-connector-j-8.0.33.jar");
            }
            params.put("lhSparkSubmitScript", SparkSubmitBuilder.buildShell(n, sql, conf));
        } else if ("DATAX".equals(dsTaskType) || "datax".equalsIgnoreCase(engine)) {
            Map<String, Object> job = DataxJobBuilder.buildJob(n, conf);
            params.put("lhDataxJob", job);
            params.put("customConfig", 1);
            params.put("json", JSONUtil.toJsonStr(job));
            params.put("rawScript", DataxJobBuilder.buildShell(n, conf));
        } else {
            // SHELL 通用：优先 Trino，否则打印 SQL
            params.put("rawScript", shellWrapper(n, engine, sql, false));
        }
        return params;
    }

    /** 供门户本地试跑 / DS 任务脚本投影 */
    public static String resolveSql(IgEtlNode n, JSONObject conf) {
        if (conf == null) {
            conf = JSONUtil.createObj();
        }
        String sql = firstNonBlank(conf.getStr("sql"), conf.getStr("query"), conf.getStr("statement"));
        if (StrUtil.isNotBlank(sql)) {
            return sql.trim();
        }
        String table = firstNonBlank(
                conf.getStr("table"),
                conf.getStr("src"),
                conf.getStr("target"),
                conf.getStr("objectName"),
                firstTable(conf));

        String type = StrUtil.blankToDefault(n.getNodeType(), "");
        if ("clean".equals(type)) {
            if (StrUtil.isNotBlank(conf.getStr("_lhCleanTable"))
                    || StrUtil.isNotBlank(conf.getStr("_lhUpstreamTable"))) {
                return CleanSqlCompiler.compileSparkJdbcPersist(
                        conf, conf.getStr("_lhUpstreamTable"), conf.getStr("_lhCleanTable"));
            }
            return CleanSqlCompiler.compile(conf, conf.getStr("_lhUpstreamTable"));
        }
        if (type.startsWith("sink_")) {
            if ("sink_iceberg".equals(type) && isFlinkEngine(conf)) {
                return FlinkIcebergSinkSqlCompiler.compile(n, conf);
            }
            // 汇：Iceberg/湖表才默认 catalog；RDB sink 用 database.table
            String fq = qualifyForSink(type, conf, table);
            String selectSql = firstNonBlank(conf.getStr("selectSql"), conf.getStr("fromSql"));
            if (StrUtil.isNotBlank(selectSql)) {
                return "INSERT INTO " + fq + "\n" + selectSql.trim();
            }
            String up = conf.getStr("_lhUpstreamTable");
            if (StrUtil.isNotBlank(table) && StrUtil.isNotBlank(up)) {
                return "-- sink " + n.getNodeKey() + "\n"
                        + "INSERT INTO " + fq + "\nSELECT * FROM " + up;
            }
            if (StrUtil.isNotBlank(table)) {
                return "-- sink " + n.getNodeKey() + "\n"
                        + "CREATE TABLE IF NOT EXISTS " + fq + " AS SELECT 1 AS _lh_probe WHERE 1=0";
            }
            return "SELECT 1 AS _lh_sink_probe";
        }
        if ("mapping".equals(type)) {
            String compiled = MappingSqlCompiler.compile(conf, conf.getStr("_lhUpstreamTable"));
            if (StrUtil.isNotBlank(compiled)) {
                return compiled;
            }
        }
        // 注意：主类型是 "source"（非 source_*）；source_api/source_file 才带下划线前缀
        if (IgEtlNodeTypes.SOURCE.contains(type) || "transform".equals(type) || "mapping".equals(type)) {
            if (IgEtlNodeTypes.SOURCE.contains(type)) {
                return buildSourceSql(n, conf, table);
            }
            if (StrUtil.isNotBlank(table)) {
                return applyOptionalLimit("SELECT * FROM " + qualifyRdbOrBare(conf, table), conf);
            }
            String up = conf.getStr("_lhUpstreamTable");
            if (StrUtil.isNotBlank(up)) {
                return applyOptionalLimit("SELECT * FROM " + up, conf);
            }
            return "SELECT 1 AS _lh_source_probe";
        }
        if ("quality".equals(type)) {
            return "SELECT 1 AS _lh_quality_ok";
        }
        // 控制流节点：SWITCH/DEPENDENT 不执行 SQL；保留可读占位避免误用 _lh_node 探针
        if ("parallel".equals(type) || "condition".equals(type) || "union".equals(type)) {
            return "SELECT 1 AS _lh_control_" + safeIdent(type);
        }
        return "SELECT 1 AS _lh_node_" + safeIdent(n.getNodeKey());
    }

    /**
     * 源节点 SQL：按数据源类型投影，禁止把 MySQL 等 RDB 误写成 iceberg.default。
     * <p>LIMIT 仅当 conf.previewLimit / conf.limit 显式给出时追加（发布全量不加）。</p>
     */
    static String buildSourceSql(IgEtlNode n, JSONObject conf, String table) {
        if (StrUtil.isBlank(table)) {
            return "SELECT 1 AS _lh_source_probe";
        }
        String dbType = detectDbType(conf);
        String database = firstNonBlank(
                conf.getStr("database"), conf.getStr("schema"), conf.getStr("lhDatabase"));
        String tableOnly = bareTable(table);
        boolean cdc = isCdcConf(conf);
        boolean flinkEng = "flink".equalsIgnoreCase(conf.getStr("_lhEngine"));

        // Flink：多方言 CDC/JDBC CREATE TABLE（列由 conf._lhColumnObjs / fieldRules / pk）
        if (isRdbFamily(dbType) && (cdc || flinkEng)) {
            List<FlinkSourceSqlCompiler.Column> cols = FlinkSourceSqlCompiler.coerceColumns(conf.get("_lhColumnObjs"));
            if (cols.isEmpty()) {
                cols = FlinkSourceSqlCompiler.columnsFromConf(conf);
            }
            return applyOptionalLimit(
                    FlinkSourceSqlCompiler.compile(FlinkSourceSqlCompiler.wrap(n), conf, cols), conf);
        }
        if (isRdbFamily(dbType)) {
            String fq = qualifyRdb(database, tableOnly, table);
            return applyOptionalLimit("SELECT * FROM " + fq, conf);
        }
        // 显式 Iceberg/湖仓源才用 catalog.schema.table
        if (StrUtil.isNotBlank(conf.getStr("catalog")) || StrUtil.isNotBlank(conf.getStr("gravCatalog"))
                || "iceberg".equalsIgnoreCase(dbType)) {
            String catalog = firstNonBlank(conf.getStr("catalog"), conf.getStr("gravCatalog"), "iceberg");
            String schema = firstNonBlank(conf.getStr("schema"), conf.getStr("database"), conf.getStr("layer"), "default");
            return applyOptionalLimit("SELECT * FROM " + qualify(catalog, schema, tableOnly), conf);
        }
        // 未知类型但带了 database：按 RDB 处理，绝不默认 iceberg
        if (StrUtil.isNotBlank(database)) {
            return applyOptionalLimit("SELECT * FROM " + qualifyRdb(database, tableOnly, table), conf);
        }
        return applyOptionalLimit("SELECT * FROM " + quoteIdent(tableOnly), conf);
    }

    private static String qualifyForSink(String type, JSONObject conf, String table) {
        if ("sink_rdb".equals(type) || "sink_ck".equals(type) || isRdbFamily(detectDbType(conf))) {
            String database = firstNonBlank(conf.getStr("database"), conf.getStr("schema"), conf.getStr("lhDatabase"));
            return qualifyRdb(database, bareTable(table), table);
        }
        String catalog = FlinkIcebergSinkSqlCompiler.lakeCatalog(conf);
        String schema = firstNonBlank(conf.getStr("schema"), conf.getStr("database"), conf.getStr("layer"), "ods");
        return qualify(catalog, schema, table);
    }

    private static String qualifyRdbOrBare(JSONObject conf, String table) {
        String database = firstNonBlank(conf.getStr("database"), conf.getStr("schema"), conf.getStr("lhDatabase"));
        return qualifyRdb(database, bareTable(table), table);
    }

    private static String qualifyRdb(String database, String tableOnly, String rawTable) {
        if (StrUtil.isNotBlank(rawTable) && rawTable.contains(".")) {
            return quoteParts(rawTable);
        }
        if (StrUtil.isNotBlank(database) && StrUtil.isNotBlank(tableOnly)) {
            return quoteIdent(database) + "." + quoteIdent(tableOnly);
        }
        if (StrUtil.isNotBlank(tableOnly)) {
            return quoteIdent(tableOnly);
        }
        return "`_lh_probe`";
    }

    /** 仅 conf.previewLimit / conf.limit 显式指定时追加；发布全量不加 LIMIT */
    private static String applyOptionalLimit(String sql, JSONObject conf) {
        if (StrUtil.isBlank(sql) || conf == null) {
            return sql;
        }
        Integer lim = conf.getInt("previewLimit");
        if (lim == null) {
            lim = conf.getInt("limit");
        }
        if (lim == null || lim <= 0) {
            return sql;
        }
        String s = sql.trim();
        if (s.toLowerCase().contains(" limit ")) {
            return s;
        }
        // CREATE+SELECT 多语句：只给最后一条 SELECT 加 LIMIT
        int lastSelect = s.toLowerCase().lastIndexOf("select ");
        if (lastSelect > 0 && s.substring(0, lastSelect).toLowerCase().contains("create table")) {
            return s + "\nLIMIT " + lim;
        }
        if (s.toLowerCase().startsWith("select")) {
            return s + " LIMIT " + lim;
        }
        return s;
    }

    private static boolean isCdcConf(JSONObject conf) {
        if ("cdc".equalsIgnoreCase(conf.getStr("mode")) || "cdc".equalsIgnoreCase(conf.getStr("source_mode"))) {
            return true;
        }
        String cdcEngine = StrUtil.blankToDefault(conf.getStr("cdcEngine"), conf.getStr("cdc_engine"));
        return StrUtil.isNotBlank(cdcEngine) && cdcEngine.toLowerCase().contains("flink");
    }

    private static String detectDbType(JSONObject conf) {
        return StrUtil.blankToDefault(
                firstNonBlank(
                        conf.getStr("dbType"),
                        conf.getStr("lhDsType"),
                        conf.getStr("dsType"),
                        conf.getStr("type")),
                "");
    }

    private static boolean isRdbFamily(String dbType) {
        String t = StrUtil.blankToDefault(dbType, "").toLowerCase();
        if (StrUtil.isBlank(t)) {
            return false;
        }
        return t.contains("mysql") || t.contains("mariadb") || t.contains("tidb")
                || t.contains("postgres") || t.contains("pg") || t.contains("greenplum")
                || t.contains("oracle") || t.contains("sqlserver") || t.contains("mssql")
                || t.contains("clickhouse") || t.contains("ck") || t.contains("db2")
                || t.contains("hive") || t.contains("doris") || "rdb".equals(t);
    }

    private static String bareTable(String table) {
        if (StrUtil.isBlank(table)) {
            return table;
        }
        String t = table.trim();
        int dot = t.lastIndexOf('.');
        return dot >= 0 ? t.substring(dot + 1) : t;
    }

    public static String resolveSql(IgEtlNode n) {
        return resolveSql(n, parseConf(n.getConfJson()));
    }

    /** 门户 conf.dsType / 数据源类型 → DS SQL 插件 type */
    public static String mapDsSqlType(JSONObject conf) {
        String explicit = firstNonBlank(conf.getStr("dsDatasourceType"), conf.getStr("sqlType"), conf.getStr("dbType"));
        if (StrUtil.isNotBlank(explicit)) {
            return explicit.trim().toUpperCase();
        }
        String t = StrUtil.blankToDefault(conf.getStr("lhDsType"), conf.getStr("type")).toLowerCase();
        if (t.contains("mysql")) {
            return "MYSQL";
        }
        if (t.contains("postgres") || t.contains("pg") || t.contains("greenplum")) {
            return "POSTGRESQL";
        }
        if (t.contains("oracle")) {
            return "ORACLE";
        }
        if (t.contains("sqlserver") || t.contains("mssql")) {
            return "SQLSERVER";
        }
        if (t.contains("clickhouse") || t.contains("ck")) {
            return "CLICKHOUSE";
        }
        if (t.contains("hive")) {
            return "HIVE";
        }
        if (t.contains("trino") || t.contains("presto")) {
            return "PRESTO";
        }
        return "POSTGRESQL";
    }

    private static String trinoShellFallback(IgEtlNode n, String sql) {
        return shellWrapper(n, "ds_sql", sql, false);
    }

    private static String shellWrapper(IgEtlNode n, String engine, String sql, boolean engineHint) {
        String escaped = sql.replace("'", "'\"'\"'");
        StringBuilder sb = new StringBuilder();
        sb.append("#!/bin/bash\n");
        sb.append("set -euo pipefail\n");
        sb.append("NODE_KEY='").append(n.getNodeKey()).append("'\n");
        sb.append("NODE_TYPE='").append(n.getNodeType()).append("'\n");
        sb.append("ENGINE='").append(engine).append("'\n");
        sb.append("SQL='").append(escaped).append("'\n");
        sb.append("echo \"[lh-etl] node=$NODE_KEY type=$NODE_TYPE engine=$ENGINE\"\n");
        if (engineHint) {
            sb.append("echo \"[lh-etl] hint=submit-").append(engine).append("\"\n");
        }
        sb.append("if [ -n \"${LH_TRINO_URL:-}\" ] && command -v trino >/dev/null 2>&1; then\n");
        sb.append("  trino --server \"$LH_TRINO_URL\" --user \"${LH_TRINO_USER:-lakehouse}\" --execute \"$SQL\"\n");
        sb.append("elif [ -n \"${LH_JDBC_URL:-}\" ] && command -v psql >/dev/null 2>&1 && echo \"$LH_JDBC_URL\" | grep -qi postgres; then\n");
        sb.append("  PGPASSWORD=\"${LH_JDBC_PASSWORD:-}\" psql \"$LH_JDBC_URL\" -c \"$SQL\"\n");
        sb.append("elif [ -n \"${LH_TRINO_URL:-}\" ] && command -v curl >/dev/null 2>&1; then\n");
        sb.append("  curl -sS -u \"${LH_TRINO_USER:-lakehouse}:${LH_TRINO_PASSWORD:-}\" \\\n");
        sb.append("    -H 'X-Trino-Catalog: iceberg' -H 'X-Trino-Schema: default' \\\n");
        sb.append("    -H \"X-Trino-User: ${LH_TRINO_USER:-lakehouse}\" \\\n");
        sb.append("    --data-binary \"$SQL\" \"${LH_TRINO_URL%/}/v1/statement\"\n");
        sb.append("else\n");
        sb.append("  echo \"[lh-etl] dry-run (no trino/jdbc); sql=$SQL\"\n");
        sb.append("fi\n");
        return sb.toString();
    }

    private static JSONObject parseConf(String confJson) {
        if (StrUtil.isBlank(confJson)) {
            return JSONUtil.createObj();
        }
        try {
            return JSONUtil.parseObj(confJson);
        } catch (Exception e) {
            return JSONUtil.createObj();
        }
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

    private static String qualify(String catalog, String schema, String table) {
        if (StrUtil.isBlank(table)) {
            return "iceberg.default._lh_probe";
        }
        if (table.contains(".")) {
            return quoteParts(table);
        }
        return quoteIdent(catalog) + "." + quoteIdent(schema) + "." + quoteIdent(table);
    }

    private static String quoteParts(String fq) {
        String[] p = fq.split("\\.");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < p.length; i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(quoteIdent(p[i]));
        }
        return sb.toString();
    }

    /** Flink SQL 用反引号；双引号会 ParseException Encountered {@code "\""}。 */
    private static String quoteIdent(String s) {
        if (StrUtil.isBlank(s)) {
            return "`_`";
        }
        return "`" + s.replace("`", "") + "`";
    }

    private static boolean isFlinkEngine(JSONObject conf) {
        if (conf == null) {
            return false;
        }
        String e = StrUtil.blankToDefault(conf.getStr("_lhEngine"), conf.getStr("engine")).toLowerCase();
        return e.startsWith("flink") || StrUtil.isBlank(e);
    }

    private static boolean isQuery(String sql) {
        String s = sql.trim().toLowerCase();
        return s.startsWith("select") || s.startsWith("show") || s.startsWith("describe") || s.startsWith("with");
    }

    private static String safeIdent(String s) {
        return StrUtil.blankToDefault(s, "x").replaceAll("[^a-zA-Z0-9_]", "_");
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
