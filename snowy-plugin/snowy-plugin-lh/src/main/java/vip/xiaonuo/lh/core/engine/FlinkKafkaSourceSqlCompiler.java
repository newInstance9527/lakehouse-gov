package vip.xiaonuo.lh.core.engine;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Flink SQL：Kafka JSON 源表（埋点事件等）。
 * <p>消息建议<strong>单条事件 JSON</strong>（与 {@code sdks/web-analytics} 信封字段对齐），非批包。</p>
 */
public final class FlinkKafkaSourceSqlCompiler {

    private FlinkKafkaSourceSqlCompiler() {
    }

    /** 埋点默认列（camelCase，对齐 SDK 信封） */
    public static List<FlinkSourceSqlCompiler.Column> defaultAnalyticsColumns() {
        List<FlinkSourceSqlCompiler.Column> cols = new ArrayList<>();
        cols.add(new FlinkSourceSqlCompiler.Column("eventId", "STRING", true));
        cols.add(new FlinkSourceSqlCompiler.Column("eventName", "STRING", false));
        cols.add(new FlinkSourceSqlCompiler.Column("eventTime", "STRING", false));
        cols.add(new FlinkSourceSqlCompiler.Column("source", "STRING", false));
        cols.add(new FlinkSourceSqlCompiler.Column("userId", "STRING", false));
        cols.add(new FlinkSourceSqlCompiler.Column("deviceId", "STRING", false));
        cols.add(new FlinkSourceSqlCompiler.Column("sessionId", "STRING", false));
        cols.add(new FlinkSourceSqlCompiler.Column("requestId", "STRING", false));
        cols.add(new FlinkSourceSqlCompiler.Column("appKey", "STRING", false));
        cols.add(new FlinkSourceSqlCompiler.Column("appVersion", "STRING", false));
        cols.add(new FlinkSourceSqlCompiler.Column("sdkVersion", "STRING", false));
        cols.add(new FlinkSourceSqlCompiler.Column("os", "STRING", false));
        cols.add(new FlinkSourceSqlCompiler.Column("page", "STRING", false));
        cols.add(new FlinkSourceSqlCompiler.Column("props", "STRING", false));
        return cols;
    }

    public static String kafkaAlias(String topic) {
        return "_lh_kfk_" + safeIdent(StrUtil.blankToDefault(topic, "events"));
    }

    /**
     * 仅 CREATE Kafka 源表（用于探测 / 文档）；完整入湖见
     * {@link FlinkIcebergSinkSqlCompiler#compileKafkaToIceberg}.
     */
    public static String compileCreateOnly(JSONObject conf) {
        if (conf == null) {
            conf = new JSONObject();
        }
        String topic = topicOf(conf);
        if (StrUtil.isBlank(topic)) {
            return "SELECT 1 AS _lh_kafka_probe";
        }
        String alias = kafkaAlias(topic);
        List<FlinkSourceSqlCompiler.Column> cols = resolveColumns(conf);
        StringBuilder sb = new StringBuilder();
        sb.append("SET 'execution.runtime-mode' = 'STREAMING';\n");
        sb.append("-- source_kafka topic=").append(topic).append('\n');
        sb.append(compileKafkaCreateTable(conf, alias, topic, cols));
        sb.append("SELECT * FROM `").append(alias).append("` /* limit via sql-client if needed */;\n");
        return sb.toString();
    }

    public static String compileKafkaCreateTable(
            JSONObject conf, String alias, String topic, List<FlinkSourceSqlCompiler.Column> columns) {
        List<FlinkSourceSqlCompiler.Column> usable = columns == null || columns.isEmpty()
                ? defaultAnalyticsColumns() : columns;
        String bootstrap = bootstrapOf(conf);
        String groupId = firstNonBlank(conf.getStr("groupId"), conf.getStr("consumerGroup"),
                "lh-etl-" + safeIdent(topic));
        String startup = firstNonBlank(conf.getStr("startupMode"), conf.getStr("scanStartupMode"), "latest-offset");
        String format = StrUtil.blankToDefault(conf.getStr("valueFormat"), "json").toLowerCase(Locale.ROOT);
        if (format.contains("json")) {
            format = "json";
        } else if (format.contains("string") || format.contains("raw")) {
            format = "raw";
        }

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
        sb.append("  'properties.bootstrap.servers' = '").append(esc(bootstrap)).append("',\n");
        sb.append("  'properties.group.id' = '").append(esc(groupId)).append("',\n");
        sb.append("  'scan.startup.mode' = '").append(esc(startup)).append("',\n");
        sb.append("  'format' = '").append(esc(format)).append("'");
        if ("json".equals(format)) {
            sb.append(",\n  'json.ignore-parse-errors' = 'true'");
            sb.append(",\n  'json.fail-on-missing-field' = 'false'");
        }
        appendKafkaSecurity(sb, conf);
        sb.append("\n);\n");
        return sb.toString();
    }

    public static String topicOf(JSONObject conf) {
        return firstNonBlank(conf.getStr("topic"), conf.getStr("table"), conf.getStr("src"),
                conf.getStr("objectName"));
    }

    public static String bootstrapOf(JSONObject conf) {
        String b = firstNonBlank(
                conf.getStr("bootstrap"),
                conf.getStr("bootstrapServers"),
                conf.getStr("lhBootstrap"),
                conf.getStr("host"),
                conf.getStr("lhHost"));
        if (StrUtil.isBlank(b)) {
            return "${LH_KAFKA_BOOTSTRAP}";
        }
        String port = firstNonBlank(conf.getStr("port"), conf.getStr("lhPort"));
        if (StrUtil.isNotBlank(port) && !b.contains(":")) {
            return b + ":" + port;
        }
        return b;
    }

    public static List<FlinkSourceSqlCompiler.Column> resolveColumns(JSONObject conf) {
        List<FlinkSourceSqlCompiler.Column> cols = FlinkSourceSqlCompiler.coerceColumns(conf.get("_lhColumnObjs"));
        if (cols.isEmpty()) {
            cols = FlinkSourceSqlCompiler.columnsFromConf(conf);
        }
        if (cols.isEmpty() || "analytics".equalsIgnoreCase(conf.getStr("schemaTemplate"))
                || "pc_app".equalsIgnoreCase(conf.getStr("schemaTemplate"))) {
            return defaultAnalyticsColumns();
        }
        return cols;
    }

    /**
     * SASL：协议/机制可写进 SQL；用户口令只用 {@code ${LH_KAFKA_USER}}/{@code ${LH_KAFKA_PASSWORD}} 占位，
     * 由 Vault→env/localParams 注入，禁止把明文写进 rawScript。
     */
    private static void appendKafkaSecurity(StringBuilder sb, JSONObject conf) {
        String security = firstNonBlank(conf.getStr("security"), conf.getStr("securityProtocol"),
                conf.getStr("security.protocol"));
        boolean needSasl = Boolean.TRUE.equals(conf.getBool("_lhKafkaSasl"))
                || StrUtil.isNotBlank(conf.getStr("saslMech"))
                || StrUtil.isNotBlank(conf.getStr("sasl.mechanism"));
        if (StrUtil.isBlank(security) || "PLAINTEXT".equalsIgnoreCase(security)) {
            if (!needSasl) {
                return;
            }
            security = "SASL_PLAINTEXT";
        }
        sb.append(",\n  'properties.security.protocol' = '").append(esc(security.trim())).append("'");
        if (!security.toUpperCase(Locale.ROOT).contains("SASL") && !needSasl) {
            return;
        }
        String mech = StrUtil.blankToDefault(firstNonBlank(conf.getStr("saslMech"), conf.getStr("sasl.mechanism")), "PLAIN");
        sb.append(",\n  'properties.sasl.mechanism' = '").append(esc(mech)).append("'");
        String module = mech.toUpperCase(Locale.ROOT).startsWith("SCRAM")
                ? "org.apache.kafka.common.security.scram.ScramLoginModule"
                : "org.apache.kafka.common.security.plain.PlainLoginModule";
        // 占位符在 Worker / DS localParams 展开；切勿嵌入 conf.password
        String jaas = module + " required username=\"${LH_KAFKA_USER}\" password=\"${LH_KAFKA_PASSWORD}\";";
        sb.append(",\n  'properties.sasl.jaas.config' = '").append(esc(jaas)).append("'");
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
