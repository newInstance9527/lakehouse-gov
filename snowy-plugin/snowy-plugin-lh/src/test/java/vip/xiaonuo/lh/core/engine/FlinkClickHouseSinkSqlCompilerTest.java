package vip.xiaonuo.lh.core.engine;

import cn.hutool.json.JSONObject;
import org.junit.jupiter.api.Test;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlNode;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlinkClickHouseSinkSqlCompilerTest {

    @Test
    void kafka_to_ck_streaming_uses_jdbc_clickhouse() {
        IgEtlNode n = new IgEtlNode();
        n.setNodeKey("n_ck");
        JSONObject conf = new JSONObject();
        conf.set("_lhFromKafka", true);
        conf.set("_lhKafkaTopic", "pc_app_events");
        conf.set("bootstrap", "kafka:9092");
        conf.set("database", "ads");
        conf.set("table", "evt_hot");
        conf.set("schemaTemplate", "analytics");

        String sql = FlinkClickHouseSinkSqlCompiler.compile(n, conf);

        assertTrue(sql.contains("SET 'execution.runtime-mode' = 'STREAMING'"), sql);
        assertTrue(sql.contains("'connector' = 'kafka'"), sql);
        assertTrue(sql.contains("'connector' = 'jdbc'"), sql);
        assertTrue(sql.contains("${LH_CK_JDBC_URL}") || sql.contains("jdbc:clickhouse://"), sql);
        assertTrue(sql.contains("'username' = '${LH_CK_USER}'"), sql);
        assertTrue(sql.contains("'table-name' = 'ads.evt_hot'"), sql);
        assertFalse(sql.contains("\""), sql);
    }

    @Test
    void jdbc_batch_to_ck() {
        IgEtlNode n = new IgEtlNode();
        n.setNodeKey("n_ck_b");
        JSONObject conf = new JSONObject();
        conf.set("_lhEngine", "flink");
        conf.set("database", "ads");
        conf.set("table", "dim_user");
        conf.set("_lhUpstreamTable", "lakehouse_gov.lh_clean_user");
        conf.set("lhDatabase", "lakehouse_gov");
        conf.set("host", "127.0.0.1");
        conf.set("port", "7306");
        conf.set("dbType", "mysql");
        conf.set("jdbcUrl", "jdbc:clickhouse://ck:8123/ads");

        String sql = FlinkClickHouseSinkSqlCompiler.compile(n, conf);
        assertTrue(sql.contains("SET 'execution.runtime-mode' = 'BATCH'"), sql);
        assertTrue(sql.contains("'table-name' = 'lh_clean_user'") || sql.contains("lh_clean_user"), sql);
        assertTrue(sql.contains("jdbc:clickhouse://ck:8123/ads"), sql);
    }

    @Test
    void resolveSql_routes_flink_sink_ck() {
        IgEtlNode n = new IgEtlNode();
        n.setNodeKey("n_sink");
        n.setNodeType("sink_ck");
        JSONObject conf = new JSONObject();
        conf.set("_lhEngine", "flink");
        conf.set("_lhFromKafka", true);
        conf.set("_lhKafkaTopic", "t1");
        conf.set("bootstrap", "localhost:9092");
        conf.set("database", "ads");
        conf.set("table", "hot");
        String sql = DsTaskScriptBuilder.resolveSql(n, conf);
        assertTrue(sql.contains("'connector' = 'jdbc'"), sql);
        assertTrue(sql.contains("${LH_CK_USER}"), sql);
    }
}
