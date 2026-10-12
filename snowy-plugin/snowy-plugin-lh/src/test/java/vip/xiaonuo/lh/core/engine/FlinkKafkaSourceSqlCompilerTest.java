package vip.xiaonuo.lh.core.engine;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import org.junit.jupiter.api.Test;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlNode;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlinkKafkaSourceSqlCompilerTest {

    @Test
    void compileCreateOnly_kafka_json_analytics_schema() {
        JSONObject conf = new JSONObject();
        conf.set("topic", "pc_app_events");
        conf.set("bootstrap", "kafka-1:9092");
        conf.set("schemaTemplate", "analytics");
        conf.set("groupId", "lh-etl-pc");
        conf.set("startupMode", "earliest-offset");

        String sql = FlinkKafkaSourceSqlCompiler.compileCreateOnly(conf);

        assertTrue(sql.contains("SET 'execution.runtime-mode' = 'STREAMING'"), sql);
        assertTrue(sql.contains("'connector' = 'kafka'"), sql);
        assertTrue(sql.contains("'topic' = 'pc_app_events'"), sql);
        assertTrue(sql.contains("'properties.bootstrap.servers' = 'kafka-1:9092'"), sql);
        assertTrue(sql.contains("'properties.group.id' = 'lh-etl-pc'"), sql);
        assertTrue(sql.contains("'scan.startup.mode' = 'earliest-offset'"), sql);
        assertTrue(sql.contains("'format' = 'json'"), sql);
        assertTrue(sql.contains("`eventId` STRING"), sql);
        assertTrue(sql.contains("`sessionId` STRING"), sql);
        assertFalse(sql.contains("\""), sql);
    }

    @Test
    void compileKafkaToIceberg_single_streaming_job() {
        IgEtlNode n = new IgEtlNode();
        n.setNodeKey("n_sink_kfk");
        n.setNodeType("sink_iceberg");
        JSONObject conf = new JSONObject();
        conf.set("_lhFromKafka", true);
        conf.set("_lhEngine", "flink");
        conf.set("topic", "pc_app_events");
        conf.set("bootstrap", "10.0.0.10:9092");
        conf.set("catalog", "prod_catalog");
        conf.set("database", "ods");
        conf.set("table", "pc_app_event");
        conf.set("schemaTemplate", "analytics");
        conf.set("warehouse", "s3://iceberg-warehouse/prod");
        conf.set("partition", "eventName");

        String sql = FlinkIcebergSinkSqlCompiler.compile(n, conf);

        assertTrue(FlinkIcebergSinkSqlCompiler.isKafkaPipeline(conf));
        assertTrue(sql.contains("SET 'execution.runtime-mode' = 'STREAMING'"), sql);
        assertTrue(sql.contains("'connector' = 'kafka'"), sql);
        assertTrue(sql.contains("'connector' = 'iceberg'"), sql);
        assertTrue(sql.contains("'catalog-name' = 'iceberg'"), sql);
        assertTrue(sql.contains("'catalog-table' = 'pc_app_event'"), sql);
        assertTrue(sql.contains("INSERT INTO `_lh_sink_pc_app_event`"), sql);
        assertTrue(sql.contains("SELECT * FROM `_lh_kfk_pc_app_events`"), sql);
        assertFalse(sql.contains("'connector' = 'jdbc'"), sql);
        assertFalse(sql.contains("\""), sql);
    }

    @Test
    void sasl_uses_vault_placeholders_not_plaintext() {
        JSONObject conf = new JSONObject();
        conf.set("topic", "sec");
        conf.set("bootstrap", "k:9092");
        conf.set("schemaTemplate", "analytics");
        conf.set("security", "SASL_PLAINTEXT");
        conf.set("saslMech", "PLAIN");
        conf.set("user", "should-not-appear");
        conf.set("password", "SuperSecret!!");
        String sql = FlinkKafkaSourceSqlCompiler.compileCreateOnly(conf);
        assertTrue(sql.contains("SASL_PLAINTEXT"), sql);
        assertTrue(sql.contains("${LH_KAFKA_USER}"), sql);
        assertTrue(sql.contains("${LH_KAFKA_PASSWORD}"), sql);
        assertFalse(sql.contains("SuperSecret"), sql);
        assertFalse(sql.contains("should-not-appear"), sql);
    }

    @Test
    void resolveSql_routes_kafka_pipeline() {
        IgEtlNode n = new IgEtlNode();
        n.setNodeKey("n_sink");
        n.setNodeType("sink_iceberg");
        JSONObject conf = new JSONObject();
        conf.set("_lhEngine", "flink");
        conf.set("_lhFromKafka", true);
        conf.set("topic", "events");
        conf.set("bootstrap", "localhost:9092");
        conf.set("catalog", "iceberg");
        conf.set("database", "ods");
        conf.set("table", "ods_events");
        conf.set("schemaTemplate", "analytics");
        String sql = DsTaskScriptBuilder.resolveSql(n, conf);
        assertTrue(sql.contains("'connector' = 'kafka'"), sql);
        assertTrue(sql.contains("STREAMING"), sql);
    }

    @Test
    void mapDsTaskType_source_kafka_is_shell_placeholder() {
        assertTrue("SHELL".equals(DsWorkflowBuilder.mapDsTaskType("source_kafka", "flink", "DS_FLINK_OR_SHELL")));
    }

    @Test
    void compileKafkaToIceberg_folds_clean_trim() {
        IgEtlNode n = new IgEtlNode();
        n.setNodeKey("n_sink");
        n.setNodeType("sink_iceberg");
        JSONObject conf = new JSONObject();
        conf.set("_lhFromKafka", true);
        conf.set("_lhEngine", "flink");
        conf.set("topic", "pc_app_events");
        conf.set("bootstrap", "kafka:9092");
        conf.set("catalog", "iceberg");
        conf.set("database", "ods");
        conf.set("table", "pc_app_event");
        conf.set("schemaTemplate", "analytics");
        JSONObject clean = new JSONObject();
        clean.set("dedup", true);
        JSONArray rules = new JSONArray();
        JSONObject r = new JSONObject();
        r.set("field", "userId");
        JSONArray ops = new JSONArray();
        ops.add("trim");
        r.set("ops", ops);
        rules.add(r);
        clean.set("fieldRules", rules);
        JSONArray process = new JSONArray();
        JSONObject step = new JSONObject();
        step.set("type", "clean");
        step.set("conf", clean);
        process.add(step);
        conf.set("_lhStreamProcess", process);

        String sql = FlinkIcebergSinkSqlCompiler.compile(n, conf);
        assertTrue(sql.contains("STREAMING"), sql);
        assertTrue(sql.contains("TRIM(`userId`)"), sql);
        assertFalse(sql.contains("ROW_NUMBER"), sql);
        assertTrue(sql.contains("INSERT INTO `_lh_sink_pc_app_event`"), sql);
    }

    @Test
    void mergeKafkaUpstream_walks_clean_to_source() {
        IgEtlNode kafka = new IgEtlNode();
        kafka.setNodeKey("n_k");
        kafka.setNodeType("source_kafka");
        kafka.setConfJson("{\"topic\":\"pc_app_events\",\"bootstrap\":\"k:9092\",\"schemaTemplate\":\"analytics\",\"dsId\":\"ds1\"}");
        IgEtlNode clean = new IgEtlNode();
        clean.setNodeKey("n_c");
        clean.setNodeType("clean");
        clean.setConfJson("{\"fieldRules\":[{\"field\":\"page\",\"ops\":[\"trim\"]}],\"_lhOnKafkaPipeline\":true,\"_lhKafkaAlias\":\"_lh_kfk_pc_app_events\"}");
        Map<String, IgEtlNode> byKey = new java.util.LinkedHashMap<>();
        byKey.put("n_k", kafka);
        byKey.put("n_c", clean);
        Map<String, java.util.List<String>> preds = new java.util.LinkedHashMap<>();
        preds.put("n_c", java.util.List.of("n_k"));
        preds.put("n_sink", java.util.List.of("n_c"));
        JSONObject sink = new JSONObject();
        sink.set("catalog", "iceberg");
        sink.set("database", "ods");
        sink.set("table", "pc_app_event");
        DsWorkflowBuilder.mergeKafkaUpstreamIntoSink(sink, java.util.List.of("n_c"), byKey, preds);
        assertTrue(Boolean.TRUE.equals(sink.getBool("_lhFromKafka")) || "true".equalsIgnoreCase(sink.getStr("_lhFromKafka")));
        assertTrue("pc_app_events".equals(sink.getStr("topic")), sink.toString());
        assertTrue(sink.get("_lhStreamProcess") != null, sink.toString());
        String sql = FlinkIcebergSinkSqlCompiler.compileKafkaToIceberg(new IgEtlNode(), sink);
        assertTrue(sql.contains("TRIM(`page`)"), sql);
    }

    @Test
    void mergeKafkaUpstream_does_not_copy_password() {
        IgEtlNode kafka = new IgEtlNode();
        kafka.setNodeKey("n_k");
        kafka.setNodeType("source_kafka");
        kafka.setConfJson("{\"topic\":\"t1\",\"bootstrap\":\"k:9092\",\"security\":\"SASL_SSL\","
                + "\"user\":\"alice\",\"password\":\"Secret!!\",\"saslMech\":\"PLAIN\"}");
        Map<String, IgEtlNode> byKey = Map.of("n_k", kafka);
        Map<String, java.util.List<String>> preds = Map.of("n_sink", java.util.List.of("n_k"));
        JSONObject sink = new JSONObject();
        sink.set("table", "ods_t");
        DsWorkflowBuilder.mergeKafkaUpstreamIntoSink(sink, java.util.List.of("n_k"), byKey, preds);
        assertTrue(Boolean.TRUE.equals(sink.getBool("_lhKafkaSasl")), sink.toString());
        assertFalse(StrUtil.isNotBlank(sink.getStr("password")), sink.toString());
        assertFalse(StrUtil.isNotBlank(sink.getStr("user")), sink.toString());
        String sql = FlinkKafkaSourceSqlCompiler.compileKafkaCreateTable(sink, "_lh_kfk_t1", "t1",
                FlinkKafkaSourceSqlCompiler.resolveColumns(sink));
        assertTrue(sql.contains("${LH_KAFKA_PASSWORD}"), sql);
        assertFalse(sql.contains("Secret!!"), sql);
    }
}
