package vip.xiaonuo.lh.core.engine;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlNode;

import java.util.List;

/**
 * Flink SQL：写入 ClickHouse（热查 ADS）。
 * <ul>
 *   <li>{@code _lhFromKafka=true}：STREAMING（source_kafka → sink_ck）</li>
 *   <li>否则：BATCH，JDBC 读上游落表再 JDBC 写 CK</li>
 * </ul>
 * <p>CK 侧用 Flink JDBC connector + {@code jdbc:clickhouse://}；凭证占位 {@code LH_CK_*}。</p>
 */
public final class FlinkClickHouseSinkSqlCompiler {

    private FlinkClickHouseSinkSqlCompiler() {
    }

    public static String compile(IgEtlNode n, JSONObject conf) {
        if (conf == null) {
            conf = new JSONObject();
        }
        if (FlinkIcebergSinkSqlCompiler.isKafkaPipeline(conf)) {
            return compileKafkaToClickHouse(n, conf);
        }
        return compileJdbcToClickHouse(n, conf);
    }

    public static String compileKafkaToClickHouse(IgEtlNode n, JSONObject conf) {
        String nodeKey = n == null ? "sink" : StrUtil.blankToDefault(n.getNodeKey(), "sink");
        String database = firstNonBlank(conf.getStr("database"), conf.getStr("schema"), "ads");
        String table = LhStagingTables.bareTable(firstNonBlank(
                conf.getStr("table"), conf.getStr("target"), conf.getStr("objectName")));
        String topic = firstNonBlank(conf.getStr("_lhKafkaTopic"), conf.getStr("sourceTopic"),
                FlinkKafkaSourceSqlCompiler.topicOf(conf), "pc_app_events");
        if (StrUtil.isBlank(table)) {
            return "SELECT 1 AS _lh_sink_ck_probe";
        }
        List<FlinkSourceSqlCompiler.Column> cols = FlinkKafkaSourceSqlCompiler.resolveColumns(conf);
        boolean pulsar = FlinkIcebergSinkSqlCompiler.isPulsarPipeline(conf);
        if (pulsar) {
            topic = firstNonBlank(conf.getStr("_lhPulsarTopic"), FlinkPulsarSourceSqlCompiler.topicOf(conf), topic);
        }
        String kAlias = pulsar
                ? FlinkPulsarSourceSqlCompiler.pulsarAlias(topic)
                : FlinkKafkaSourceSqlCompiler.kafkaAlias(topic);
        String sinkAlias = "_lh_ck_" + safeIdent(table);

        StringBuilder sb = new StringBuilder();
        sb.append("SET 'execution.runtime-mode' = 'STREAMING';\n");
        sb.append("-- sink ").append(nodeKey).append(" clickhouse ").append(database).append('.').append(table)
                .append(pulsar ? " ← pulsar " : " ← kafka ").append(topic).append('\n');
        sb.append(pulsar ? "-- credentials: LH_PULSAR_* / LH_CK_*（Vault）\n"
                : "-- credentials: LH_KAFKA_* / LH_CK_*（Vault）\n");
        if (pulsar) {
            sb.append(FlinkPulsarSourceSqlCompiler.compilePulsarCreateTable(conf, kAlias, topic, cols));
        } else {
            sb.append(FlinkKafkaSourceSqlCompiler.compileKafkaCreateTable(conf, kAlias, topic, cols));
        }
        sb.append(compileClickHouseJdbcTable(conf, sinkAlias, database, table, cols));
        String insertSelect = FlinkIcebergSinkSqlCompiler.buildKafkaInsertSelect(conf, kAlias);
        sb.append("INSERT INTO `").append(sinkAlias).append("`\n")
                .append(insertSelect.trim());
        if (!insertSelect.trim().endsWith(";")) {
            sb.append(';');
        }
        sb.append('\n');
        return sb.toString();
    }

    public static String compileJdbcToClickHouse(IgEtlNode n, JSONObject conf) {
        String nodeKey = n == null ? "sink" : StrUtil.blankToDefault(n.getNodeKey(), "sink");
        String database = firstNonBlank(conf.getStr("database"), conf.getStr("schema"), "ads");
        String table = LhStagingTables.bareTable(firstNonBlank(
                conf.getStr("table"), conf.getStr("target"), conf.getStr("objectName")));
        String up = firstNonBlank(conf.getStr("_lhUpstreamTable"), conf.getStr("src"));
        String landBare = LhStagingTables.bareTable(up);
        if (StrUtil.isBlank(table)) {
            return "SELECT 1 AS _lh_sink_ck_probe";
        }
        if (StrUtil.isBlank(landBare)) {
            landBare = "lh_staging";
        }
        List<FlinkSourceSqlCompiler.Column> cols = FlinkSourceSqlCompiler.coerceColumns(conf.get("_lhColumnObjs"));
        if (cols.isEmpty()) {
            cols = FlinkSourceSqlCompiler.columnsFromConf(conf);
        }
        if (cols.isEmpty()) {
            cols = List.of(new FlinkSourceSqlCompiler.Column("id", "STRING", true));
        }
        String landDb = firstNonBlank(conf.getStr("lhDatabase"), databasePart(up), conf.getStr("lhReaderDatabase"));
        if (StrUtil.isNotBlank(landDb)) {
            conf.set("lhDatabase", landDb);
        }
        String srcAlias = "_lh_src_" + safeIdent(landBare);
        String sinkAlias = "_lh_ck_" + safeIdent(table);

        StringBuilder sb = new StringBuilder();
        sb.append("SET 'execution.runtime-mode' = 'BATCH';\n");
        sb.append("-- sink ").append(nodeKey).append(" clickhouse ").append(database).append('.').append(table)
                .append(" ← jdbc ").append(StrUtil.blankToDefault(landDb, "?")).append('.').append(landBare)
                .append('\n');
        sb.append("-- credentials: LH_JDBC_*（读）/ LH_CK_*（写）\n");
        sb.append(FlinkSourceSqlCompiler.compileJdbcCreateTable(conf, srcAlias, landBare, cols));
        sb.append(compileClickHouseJdbcTable(conf, sinkAlias, database, table, cols));
        sb.append("INSERT INTO `").append(sinkAlias).append("` SELECT * FROM `").append(srcAlias).append("`;\n");
        return sb.toString();
    }

    static String compileClickHouseJdbcTable(
            JSONObject conf, String alias, String database, String table,
            List<FlinkSourceSqlCompiler.Column> columns) {
        List<FlinkSourceSqlCompiler.Column> usable = columns == null || columns.isEmpty()
                ? List.of(new FlinkSourceSqlCompiler.Column("id", "STRING", true)) : columns;
        String url = clickHouseJdbcUrl(conf, database);
        String tableName = StrUtil.isNotBlank(database) ? database + "." + table : table;

        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS `").append(safeIdent(alias)).append("` (\n");
        for (int i = 0; i < usable.size(); i++) {
            FlinkSourceSqlCompiler.Column c = usable.get(i);
            sb.append("  `").append(c.name().replace("`", "")).append("` ")
                    .append(StrUtil.blankToDefault(c.flinkType(), "STRING"));
            if (i < usable.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append(") WITH (\n");
        sb.append("  'connector' = 'jdbc',\n");
        sb.append("  'url' = '").append(esc(url)).append("',\n");
        sb.append("  'table-name' = '").append(esc(tableName)).append("',\n");
        sb.append("  'username' = '${LH_CK_USER}',\n");
        sb.append("  'password' = '${LH_CK_PASSWORD}',\n");
        sb.append("  'sink.buffer-flush.max-rows' = '")
                .append(esc(StrUtil.blankToDefault(str(conf.get("batchSize")), "5000"))).append("',\n");
        sb.append("  'sink.buffer-flush.interval' = '")
                .append(esc(flushInterval(conf))).append("'\n");
        sb.append(");\n");
        return sb.toString();
    }

    static String clickHouseJdbcUrl(JSONObject conf, String database) {
        String explicit = firstNonBlank(conf.getStr("jdbcUrl"), conf.getStr("lhCkJdbcUrl"), conf.getStr("url"));
        if (StrUtil.isNotBlank(explicit)) {
            return explicit;
        }
        String host = firstNonBlank(conf.getStr("host"), conf.getStr("lhHost"), conf.getStr("ckHost"),
                conf.getStr("lhCkHost"));
        String port = firstNonBlank(conf.getStr("port"), conf.getStr("lhPort"), conf.getStr("ckPort"), "8123");
        String db = StrUtil.blankToDefault(database, "default");
        if (StrUtil.isNotBlank(host) && !host.contains("${")) {
            return "jdbc:clickhouse://" + host + ":" + port + "/" + db;
        }
        // Vault 注入 LH_CK_JDBC_URL（或 LH_CK_HOST 拼装）
        return "${LH_CK_JDBC_URL}";
    }

    private static String flushInterval(JSONObject conf) {
        Object ms = conf == null ? null : conf.get("flushIntervalMs");
        if (ms instanceof Number) {
            long v = ((Number) ms).longValue();
            if (v > 0) {
                return (v / 1000) + "s";
            }
        }
        return "2s";
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String databasePart(String qualified) {
        if (StrUtil.isBlank(qualified) || !qualified.contains(".")) {
            return null;
        }
        String t = qualified.replace("`", "").replace("\"", "").trim();
        int dot = t.indexOf('.');
        return dot > 0 ? t.substring(0, dot) : null;
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
