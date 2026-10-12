package vip.xiaonuo.lh.core.engine;

import cn.hutool.json.JSONObject;
import org.junit.jupiter.api.Test;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlNode;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlinkKafkaSinkSqlCompilerTest {

    @Test
    void kafka_fanout_keeps_source_and_target_topics_apart() {
        IgEtlNode n = new IgEtlNode();
        n.setNodeKey("n_ksink");
        JSONObject conf = new JSONObject();
        conf.set("_lhFromKafka", true);
        conf.set("_lhKafkaTopic", "pc_app_events");
        conf.set("topic", "pc_app_events_ads");
        conf.set("bootstrap", "kafka:9092");
        conf.set("schemaTemplate", "analytics");

        String sql = FlinkKafkaSinkSqlCompiler.compile(n, conf);

        assertTrue(sql.contains("SET 'execution.runtime-mode' = 'STREAMING'"), sql);
        assertTrue(sql.contains("'topic' = 'pc_app_events'"), sql);
        assertTrue(sql.contains("'topic' = 'pc_app_events_ads'"), sql);
        assertTrue(sql.contains("'sink.delivery-guarantee'"), sql);
        assertFalse(sql.contains("\""), sql);
    }

    @Test
    void resolveSql_routes_flink_sink_kafka() {
        IgEtlNode n = new IgEtlNode();
        n.setNodeKey("n_sink");
        n.setNodeType("sink_kafka");
        JSONObject conf = new JSONObject();
        conf.set("_lhEngine", "flink");
        conf.set("_lhFromKafka", true);
        conf.set("_lhKafkaTopic", "src_t");
        conf.set("topic", "dst_t");
        conf.set("bootstrap", "localhost:9092");
        String sql = DsTaskScriptBuilder.resolveSql(n, conf);
        assertTrue(sql.contains("'connector' = 'kafka'"), sql);
        assertTrue(sql.contains("'topic' = 'dst_t'"), sql);
    }

    @Test
    void merge_preserves_sink_kafka_target_topic() {
        IgEtlNode kafka = new IgEtlNode();
        kafka.setNodeKey("n_k");
        kafka.setNodeType("source_kafka");
        kafka.setConfJson("{\"topic\":\"src_events\",\"bootstrap\":\"k:9092\",\"dsId\":\"ds_k\"}");

        IgEtlNode sink = new IgEtlNode();
        sink.setNodeKey("n_s");
        sink.setNodeType("sink_kafka");
        JSONObject sinkConf = new JSONObject();
        sinkConf.set("topic", "fanout_events");
        sinkConf.set("dsId", "ds_k2");

        java.util.Map<String, IgEtlNode> byKey = new java.util.LinkedHashMap<>();
        byKey.put("n_k", kafka);
        byKey.put("n_s", sink);
        DsWorkflowBuilder.mergeKafkaUpstreamIntoSink(
                sinkConf, java.util.List.of("n_k"), byKey, java.util.Map.of());

        assertTrue(Boolean.TRUE.equals(sinkConf.getBool("_lhFromKafka")));
        assertTrue("src_events".equals(sinkConf.getStr("_lhKafkaTopic")), sinkConf.toString());
        assertTrue("fanout_events".equals(sinkConf.getStr("topic")), sinkConf.toString());
    }
}
