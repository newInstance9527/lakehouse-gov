package vip.xiaonuo.lh.core.engine;

import cn.hutool.json.JSONObject;
import org.junit.jupiter.api.Test;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlNode;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EtlF2F3F8RabbitTest {

    @Test
    void f2_sink_rdb_dialect_writers() {
        assertWriter("postgresql", "postgresqlwriter");
        assertWriter("oracle", "oraclewriter");
        assertWriter("sqlserver", "sqlserverwriter");
        assertWriter("mysql", "mysqlwriter");
    }

    @Test
    void f3_doris_starrocks_stream_load_writers() {
        IgEtlNode n = sinkRdb();
        JSONObject conf = new JSONObject();
        conf.set("lhDsType", "doris");
        conf.set("table", "ads.orders");
        conf.set("database", "ads");
        conf.set("loadUrl", "fe1:8030,fe2:8030");
        Map<String, Object> job = DataxJobBuilder.buildJob(n, conf);
        Map<String, Object> writer = writerOf(job);
        assertEquals("doriswriter", writer.get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> param = (Map<String, Object>) writer.get("parameter");
        assertEquals("ads", param.get("database"));
        assertEquals("orders", param.get("table"));
        assertTrue(param.containsKey("loadUrl") || param.containsKey("feLoadUrl"));

        conf.set("lhDsType", "starrocks");
        Map<String, Object> sr = writerOf(DataxJobBuilder.buildJob(n, conf));
        assertEquals("starrockswriter", sr.get("name"));
    }

    @Test
    void rabbitmq_node_and_shell() {
        assertTrue(IgEtlNodeTypes.isKnown("source_rabbitmq"));
        assertTrue(LhEtlDsCapability.allowsBind("rabbitmq", "source_rabbitmq"));
        assertFalse(LhEtlDsCapability.allowsBind("rabbitmq", "source_api"));
        String sh = DsWorkflowBuilder.rabbitmqSourceShell("lh.q", "/prod");
        assertTrue(sh.contains("LH_RABBIT_CONSUMER_CMD"), sh);
        assertTrue(sh.contains("lh.q"), sh);
        assertEquals("SHELL", DsWorkflowBuilder.mapDsTaskType("source_rabbitmq", "ds_sql", "DS_SQL"));
        // 注入字符被白名单拒绝
        String bad = DsWorkflowBuilder.rabbitmqSourceShell("q'; rm -rf /", "/x;y");
        assertTrue(bad.contains("QUEUE='q'"), bad);
        assertTrue(bad.contains("VHOST='/'"), bad);
        assertEquals("safe.q", DsWorkflowBuilder.sanitizeRabbitToken("safe.q", "q"));
        assertEquals("q", DsWorkflowBuilder.sanitizeRabbitToken("a;b", "q"));
    }

    @Test
    void datax_shell_expands_oss_doris_hdfs_placeholders() {
        IgEtlNode n = sinkRdb();
        n.setNodeType("sink_object");
        JSONObject conf = new JSONObject();
        conf.set("lhDsType", "s3");
        conf.set("bucket", "b1");
        conf.set("basePath", "out/");
        String sh = DataxJobBuilder.buildShell(n, conf);
        assertTrue(sh.contains("LH_OSS_BUCKET"), sh);
        assertTrue(sh.contains("LH_HDFS_DEFAULT_FS") || sh.contains("LH_OSS_ENDPOINT"), sh);
        // expand 列表须含今日新增键
        assertTrue(sh.contains("'LH_OSS_BUCKET'") || sh.contains("LH_OSS_BUCKET"), sh);
        assertTrue(sh.contains("LH_DORIS_FE_HTTP"), sh);
    }

    @Test
    void redis_stays_excluded() {
        assertFalse(LhEtlDsCapability.allowsBind("redis", "source"));
        assertFalse(LhEtlDsCapability.allowsBind("redis", "sink_rdb"));
        assertEquals(LhEtlDsCapability.Level.excluded, LhEtlDsCapability.of("redis").ingest());
        assertEquals(LhEtlDsCapability.Level.excluded, LhEtlDsCapability.of("redis").export());
    }

    @Test
    void doris_export_ready() {
        assertEquals(LhEtlDsCapability.Level.ready, LhEtlDsCapability.of("doris").export());
        assertEquals(LhEtlDsCapability.Level.ready, LhEtlDsCapability.of("starrocks").export());
    }

    private static void assertWriter(String dsType, String expect) {
        IgEtlNode n = sinkRdb();
        JSONObject conf = new JSONObject();
        conf.set("lhDsType", dsType);
        conf.set("table", "t1");
        assertEquals(expect, writerOf(DataxJobBuilder.buildJob(n, conf)).get("name"));
    }

    private static IgEtlNode sinkRdb() {
        IgEtlNode n = new IgEtlNode();
        n.setNodeKey("n_rdb");
        n.setNodeType("sink_rdb");
        return n;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> writerOf(Map<String, Object> job) {
        Map<String, Object> content0 = ((List<Map<String, Object>>) ((Map<?, ?>) job.get("job")).get("content")).get(0);
        return (Map<String, Object>) content0.get("writer");
    }
}
