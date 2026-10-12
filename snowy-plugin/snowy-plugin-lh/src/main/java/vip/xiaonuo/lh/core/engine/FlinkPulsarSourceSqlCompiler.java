package vip.xiaonuo.lh.core.engine;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * Flink SQL：Pulsar JSON 源（对齐 source_kafka 折汇模型）。
 * <p>依赖 {@code flink-sql-connector-pulsar}（见 deploy/flink-sql-connectors.sh）。</p>
 */
public final class FlinkPulsarSourceSqlCompiler {

    private FlinkPulsarSourceSqlCompiler() {
    }

    public static String pulsarAlias(String topic) {
        return "_lh_psr_" + safeIdent(StrUtil.blankToDefault(bareTopic(topic), "events"));
    }

    public static String compileCreateOnly(JSONObject conf) {
        if (conf == null) {
            conf = new JSONObject();
        }
        String topic = topicOf(conf);
        if (StrUtil.isBlank(topic)) {
            return "SELECT 1 AS _lh_pulsar_probe";
        }
        String alias = pulsarAlias(topic);
        List<FlinkSourceSqlCompiler.Column> cols = FlinkKafkaSourceSqlCompiler.resolveColumns(conf);
        StringBuilder sb = new StringBuilder();
        sb.append("SET 'execution.runtime-mode' = 'STREAMING';\n");
        sb.append("-- source_pulsar topic=").append(topic).append('\n');
        sb.append(compilePulsarCreateTable(conf, alias, topic, cols));
        sb.append("SELECT * FROM `").append(alias).append("` /* limit via sql-client if needed */;\n");
        return sb.toString();
    }

    public static String compilePulsarCreateTable(
            JSONObject conf, String alias, String topic, List<FlinkSourceSqlCompiler.Column> columns) {
        List<FlinkSourceSqlCompiler.Column> usable = columns == null || columns.isEmpty()
                ? FlinkKafkaSourceSqlCompiler.defaultAnalyticsColumns() : columns;
        String serviceUrl = serviceUrlOf(conf);
        String adminUrl = adminUrlOf(conf);
        String subscription = firstNonBlank(conf.getStr("subscription"), conf.getStr("groupId"),
                conf.getStr("consumerGroup"), "lh-etl-" + safeIdent(bareTopic(topic)));
        String startup = firstNonBlank(conf.getStr("startupMode"), conf.getStr("source.start.message-id"), "latest");
        if ("latest-offset".equalsIgnoreCase(startup) || "latest".equalsIgnoreCase(startup)) {
            startup = "latest";
        } else if ("earliest-offset".equalsIgnoreCase(startup) || "earliest".equalsIgnoreCase(startup)) {
            startup = "earliest";
        }
        String format = StrUtil.blankToDefault(conf.getStr("valueFormat"), "json").toLowerCase(Locale.ROOT);
        if (format.contains("json")) {
            format = "json";
        } else if (format.contains("raw") || format.contains("string")) {
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
        sb.append("  'connector' = 'pulsar',\n");
        sb.append("  'topics' = '").append(esc(topic)).append("',\n");
        sb.append("  'service-url' = '").append(esc(serviceUrl)).append("',\n");
        if (StrUtil.isNotBlank(adminUrl)) {
            sb.append("  'admin-url' = '").append(esc(adminUrl)).append("',\n");
        }
        sb.append("  'source.subscription-name' = '").append(esc(subscription)).append("',\n");
        sb.append("  'source.start.message-id' = '").append(esc(startup)).append("',\n");
        sb.append("  'format' = '").append(esc(format)).append("'");
        if ("json".equals(format)) {
            sb.append(",\n  'json.ignore-parse-errors' = 'true'");
            sb.append(",\n  'json.fail-on-missing-field' = 'false'");
        }
        sb.append("\n);\n");
        return sb.toString();
    }

    public static String topicOf(JSONObject conf) {
        return firstNonBlank(conf.getStr("topic"), conf.getStr("topics"), conf.getStr("table"),
                conf.getStr("src"), conf.getStr("objectName"), conf.getStr("_lhPulsarTopic"));
    }

    public static String serviceUrlOf(JSONObject conf) {
        String u = firstNonBlank(
                conf.getStr("serviceUrl"),
                conf.getStr("brokerServiceUrl"),
                conf.getStr("bootstrap"),
                conf.getStr("lhBootstrap"),
                conf.getStr("host"));
        if (StrUtil.isBlank(u)) {
            return "${LH_PULSAR_SERVICE_URL}";
        }
        if (u.startsWith("pulsar://") || u.startsWith("pulsar+ssl://") || u.contains("${")) {
            return u;
        }
        String port = firstNonBlank(conf.getStr("port"), conf.getStr("lhPort"), "6650");
        if (!u.contains(":")) {
            return "pulsar://" + u + ":" + port;
        }
        return u.startsWith("pulsar") ? u : "pulsar://" + u;
    }

    public static String adminUrlOf(JSONObject conf) {
        String a = firstNonBlank(conf.getStr("adminUrl"), conf.getStr("webServiceUrl"), conf.getStr("httpUrl"));
        if (StrUtil.isNotBlank(a)) {
            return a;
        }
        // 可选；部分发行版可不配
        return firstNonBlank(conf.getStr("lhAdminUrl"), "");
    }

    private static String bareTopic(String topic) {
        if (StrUtil.isBlank(topic)) {
            return "events";
        }
        // persistent://tenant/ns/name → name
        int slash = topic.lastIndexOf('/');
        return slash >= 0 ? topic.substring(slash + 1) : topic;
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
