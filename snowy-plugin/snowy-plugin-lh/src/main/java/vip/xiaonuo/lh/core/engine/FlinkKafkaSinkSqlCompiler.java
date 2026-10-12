package vip.xiaonuo.lh.core.engine;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlNode;

import java.util.List;
import java.util.Locale;

/**
 * Flink SQL：写出到 Kafka（扇出 / 出湖总线）。
 * <ul>
 *   <li>{@code _lhFromKafka=true}：同作业 STREAMING（source_kafka → sink_kafka）</li>
 *   <li>否则：BATCH，JDBC 读上游 {@code lh_ods_*}/{@code lh_clean_*} 再写 Topic</li>
 * </ul>
 */
public final class FlinkKafkaSinkSqlCompiler {

    private FlinkKafkaSinkSqlCompiler() {
    }

    public static String compile(IgEtlNode n, JSONObject conf) {
        if (conf == null) {
            conf = new JSONObject();
        }
        if (FlinkIcebergSinkSqlCompiler.isKafkaPipeline(conf)) {
            return compileKafkaToKafka(n, conf);
        }
        return compileJdbcToKafka(n, conf);
    }

    /** Topic 扇出：Kafka JSON → Kafka JSON（可折叠 clean/mapping）。 */
    public static String compileKafkaToKafka(IgEtlNode n, JSONObject conf) {
        String nodeKey = n == null ? "sink" : StrUtil.blankToDefault(n.getNodeKey(), "sink");
        // 源 Topic 优先 _lhKafkaTopic（merge 写入）；sink 的 topic 是目标 Topic，勿混用
        String srcTopic = firstNonBlank(conf.getStr("_lhKafkaTopic"), conf.getStr("sourceTopic"));
        String sinkTopic = firstNonBlank(conf.getStr("targetTopic"), conf.getStr("target"), conf.getStr("topic"));
        if (StrUtil.isBlank(srcTopic)) {
            srcTopic = "pc_app_events";
        }
        if (StrUtil.isBlank(sinkTopic)) {
            return "SELECT 1 AS _lh_sink_kafka_probe";
        }
        // 扇出目标 Topic 与源相同时仍允许（测试/重投），但标注
        List<FlinkSourceSqlCompiler.Column> cols = FlinkKafkaSourceSqlCompiler.resolveColumns(conf);
        boolean pulsar = FlinkIcebergSinkSqlCompiler.isPulsarPipeline(conf);
        if (pulsar) {
            srcTopic = firstNonBlank(conf.getStr("_lhPulsarTopic"), FlinkPulsarSourceSqlCompiler.topicOf(conf), srcTopic);
        }
        String kAlias = pulsar
                ? FlinkPulsarSourceSqlCompiler.pulsarAlias(srcTopic)
                : FlinkKafkaSourceSqlCompiler.kafkaAlias(srcTopic);
        String sinkAlias = "_lh_ksink_" + safeIdent(sinkTopic);

        StringBuilder sb = new StringBuilder();
        sb.append("SET 'execution.runtime-mode' = 'STREAMING';\n");
        sb.append("-- sink ").append(nodeKey).append(" kafka ").append(sinkTopic)
                .append(pulsar ? " ← pulsar " : " ← kafka ").append(srcTopic).append('\n');
        if (pulsar) {
            sb.append(FlinkPulsarSourceSqlCompiler.compilePulsarCreateTable(conf, kAlias, srcTopic, cols));
        } else {
            sb.append(FlinkKafkaSourceSqlCompiler.compileKafkaCreateTable(conf, kAlias, srcTopic, cols));
        }
        sb.append(compileKafkaSinkTable(conf, sinkAlias, sinkTopic, cols));
        String insertSelect = FlinkIcebergSinkSqlCompiler.buildKafkaInsertSelect(conf, kAlias);
        sb.append("INSERT INTO `").append(sinkAlias).append("`\n")
                .append(insertSelect.trim());
        if (!insertSelect.trim().endsWith(";")) {
            sb.append(';');
        }
        sb.append('\n');
        return sb.toString();
    }

    /** 批：JDBC 落表 → Kafka。 */
    public static String compileJdbcToKafka(IgEtlNode n, JSONObject conf) {
        String nodeKey = n == null ? "sink" : StrUtil.blankToDefault(n.getNodeKey(), "sink");
        String sinkTopic = firstNonBlank(conf.getStr("topic"), conf.getStr("targetTopic"), conf.getStr("target"));
        String up = firstNonBlank(conf.getStr("_lhUpstreamTable"), conf.getStr("src"));
        String landBare = LhStagingTables.bareTable(up);
        if (StrUtil.isBlank(sinkTopic)) {
            return "SELECT 1 AS _lh_sink_kafka_probe";
        }
        if (StrUtil.isBlank(landBare)) {
            landBare = "lh_staging";
        }
        List<FlinkSourceSqlCompiler.Column> cols = FlinkSourceSqlCompiler.coerceColumns(conf.get("_lhColumnObjs"));
        if (cols.isEmpty()) {
            cols = FlinkSourceSqlCompiler.columnsFromConf(conf);
        }
        if (cols.isEmpty()) {
            cols = FlinkKafkaSourceSqlCompiler.defaultAnalyticsColumns();
        }
        String landDb = firstNonBlank(conf.getStr("lhDatabase"), databasePart(up), conf.getStr("lhReaderDatabase"));
        if (StrUtil.isNotBlank(landDb)) {
            conf.set("lhDatabase", landDb);
        }
        String srcAlias = "_lh_src_" + safeIdent(landBare);
        String sinkAlias = "_lh_ksink_" + safeIdent(sinkTopic);

        StringBuilder sb = new StringBuilder();
        sb.append("SET 'execution.runtime-mode' = 'BATCH';\n");
        sb.append("-- sink ").append(nodeKey).append(" kafka ").append(sinkTopic)
                .append(" ← jdbc ").append(StrUtil.blankToDefault(landDb, "?")).append('.').append(landBare)
                .append('\n');
        sb.append(FlinkSourceSqlCompiler.compileJdbcCreateTable(conf, srcAlias, landBare, cols));
        sb.append(compileKafkaSinkTable(conf, sinkAlias, sinkTopic, cols));
        sb.append("INSERT INTO `").append(sinkAlias).append("` SELECT * FROM `").append(srcAlias).append("`;\n");
        return sb.toString();
    }

    public static String compileKafkaSinkTable(
            JSONObject conf, String alias, String topic, List<FlinkSourceSqlCompiler.Column> columns) {
        List<FlinkSourceSqlCompiler.Column> usable = columns == null || columns.isEmpty()
                ? FlinkKafkaSourceSqlCompiler.defaultAnalyticsColumns() : columns;
        String bootstrap = FlinkKafkaSourceSqlCompiler.bootstrapOf(conf);
        // 写端可用独立 bootstrap（扇出到另一集群）
        String sinkBootstrap = firstNonBlank(conf.getStr("sinkBootstrap"), conf.getStr("writerBootstrap"), bootstrap);
        String format = StrUtil.blankToDefault(conf.getStr("valueFormat"), "json").toLowerCase(Locale.ROOT);
        if (format.contains("json")) {
            format = "json";
        } else if (format.contains("string") || format.contains("raw")) {
            format = "raw";
        }
        String guarantee = firstNonBlank(conf.getStr("deliveryGuarantee"), conf.getStr("sink.delivery-guarantee"),
                "at-least-once");

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
        sb.append("  'connector' = 'kafka',\n");
        sb.append("  'topic' = '").append(esc(topic)).append("',\n");
        sb.append("  'properties.bootstrap.servers' = '").append(esc(sinkBootstrap)).append("',\n");
        sb.append("  'format' = '").append(esc(format)).append("',\n");
        sb.append("  'sink.delivery-guarantee' = '").append(esc(guarantee)).append("'");
        String keyField = firstNonBlank(conf.getStr("keyField"), conf.getStr("key"));
        if (StrUtil.isNotBlank(keyField)) {
            sb.append(",\n  'key.format' = 'raw'");
            sb.append(",\n  'key.fields' = '").append(esc(keyField.replace("`", ""))).append("'");
        }
        appendProducerExtras(sb, conf);
        sb.append("\n);\n");
        return sb.toString();
    }

    private static void appendProducerExtras(StringBuilder sb, JSONObject conf) {
        String acks = conf.getStr("acks");
        if (StrUtil.isNotBlank(acks)) {
            sb.append(",\n  'properties.acks' = '").append(esc(acks)).append("'");
        }
        String compression = conf.getStr("compression");
        if (StrUtil.isNotBlank(compression) && !"none".equalsIgnoreCase(compression)) {
            sb.append(",\n  'properties.compression.type' = '").append(esc(compression)).append("'");
        }
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
