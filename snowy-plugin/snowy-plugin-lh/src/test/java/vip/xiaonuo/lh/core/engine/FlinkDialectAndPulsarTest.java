package vip.xiaonuo.lh.core.engine;

import cn.hutool.json.JSONObject;
import org.junit.jupiter.api.Test;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlNode;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlinkDialectAndPulsarTest {

    @Test
    void jdbc_url_clickhouse_doris_hive() {
        IgEtlNode n = new IgEtlNode();
        n.setNodeKey("n_src");
        n.setNodeType("source");

        JSONObject ck = baseJdbc("clickhouse", "8123");
        String ckSql = FlinkSourceSqlCompiler.compile(FlinkSourceSqlCompiler.wrap(n), ck, null);
        assertTrue(ckSql.contains("jdbc:clickhouse://"), ckSql);
        assertTrue(ckSql.contains(":8123/"), ckSql);
        assertFalse(ckSql.contains("mysql-cdc"), ckSql);

        JSONObject doris = baseJdbc("doris", "9030");
        String dSql = FlinkSourceSqlCompiler.compile(FlinkSourceSqlCompiler.wrap(n), doris, null);
        assertTrue(dSql.contains("jdbc:mysql://"), dSql);
        assertTrue(dSql.contains(":9030/"), dSql);
        assertFalse(dSql.contains("mysql-cdc"), dSql);

        JSONObject hive = baseJdbc("hive", "10000");
        String hSql = FlinkSourceSqlCompiler.compile(FlinkSourceSqlCompiler.wrap(n), hive, null);
        assertTrue(hSql.contains("jdbc:hive2://"), hSql);
    }

    @Test
    void pulsar_pipeline_compiles_into_iceberg_sink() {
        IgEtlNode n = new IgEtlNode();
        n.setNodeKey("n_sink");
        JSONObject conf = new JSONObject();
        conf.set("_lhFromPulsar", true);
        conf.set("_lhMqKind", "pulsar");
        conf.set("_lhPulsarTopic", "persistent://public/default/pc_app_events");
        conf.set("serviceUrl", "pulsar://broker:6650");
        conf.set("database", "ods");
        conf.set("table", "pc_app_event");
        conf.set("schemaTemplate", "analytics");
        String sql = FlinkIcebergSinkSqlCompiler.compile(n, conf);
        assertTrue(FlinkIcebergSinkSqlCompiler.isKafkaPipeline(conf));
        assertTrue(FlinkIcebergSinkSqlCompiler.isPulsarPipeline(conf));
        assertTrue(sql.contains("'connector' = 'pulsar'"), sql);
        assertTrue(sql.contains("SET 'execution.runtime-mode' = 'STREAMING'"), sql);
        assertTrue(sql.contains("_lh_psr_"), sql);
    }

    @Test
    void stream_mq_source_types() {
        assertTrue(FlinkIcebergSinkSqlCompiler.isStreamMqSourceType("source_kafka"));
        assertTrue(FlinkIcebergSinkSqlCompiler.isStreamMqSourceType("source_pulsar"));
        assertFalse(FlinkIcebergSinkSqlCompiler.isStreamMqSourceType("source"));
        assertTrue(IgEtlNodeTypes.isKnown("source_pulsar"));
    }

    private static JSONObject baseJdbc(String dbType, String port) {
        JSONObject conf = new JSONObject();
        conf.set("_lhEngine", "flink");
        conf.set("mode", "batch");
        conf.set("dbType", dbType);
        conf.set("host", "127.0.0.1");
        conf.set("port", port);
        conf.set("database", "demo");
        conf.set("table", "t1");
        conf.set("_lhLandingTable", "demo.lh_ods_t1");
        return conf;
    }
}
