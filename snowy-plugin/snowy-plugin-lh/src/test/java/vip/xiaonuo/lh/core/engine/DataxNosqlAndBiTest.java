package vip.xiaonuo.lh.core.engine;

import cn.hutool.json.JSONObject;
import org.junit.jupiter.api.Test;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlNode;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataxNosqlAndBiTest {

    @Test
    void source_mongo_uses_mongodbreader() {
        IgEtlNode n = new IgEtlNode();
        n.setNodeKey("n_mongo");
        n.setNodeType("source_mongo");
        JSONObject conf = new JSONObject();
        conf.set("host", "mongo");
        conf.set("port", 27017);
        conf.set("database", "app");
        conf.set("collection", "events");
        conf.set("lhDsType", "mongodb");
        Map<String, Object> job = DataxJobBuilder.buildJob(n, conf);
        @SuppressWarnings("unchecked")
        Map<String, Object> content0 = ((List<Map<String, Object>>) ((Map<?, ?>) job.get("job")).get("content")).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> reader = (Map<String, Object>) content0.get("reader");
        assertEquals("mongodbreader", reader.get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> param = (Map<String, Object>) reader.get("parameter");
        assertEquals("events", param.get("collectionName"));
        assertEquals("app", param.get("dbName"));
    }

    @Test
    void source_hbase_uses_hbasereader() {
        IgEtlNode n = new IgEtlNode();
        n.setNodeKey("n_hb");
        n.setNodeType("source_hbase");
        JSONObject conf = new JSONObject();
        conf.set("zkQuorum", "zk1,zk2");
        conf.set("table", "ns:user");
        conf.set("lhDsType", "hbase");
        Map<String, Object> job = DataxJobBuilder.buildJob(n, conf);
        @SuppressWarnings("unchecked")
        Map<String, Object> content0 = ((List<Map<String, Object>>) ((Map<?, ?>) job.get("job")).get("content")).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> reader = (Map<String, Object>) content0.get("reader");
        assertEquals("hbasereader", reader.get("name"));
    }

    @Test
    void sink_bi_shell_mentions_ticket_and_refresh() {
        IgEtlNode n = new IgEtlNode();
        n.setNodeKey("n_bi");
        n.setNodeType("sink_bi");
        JSONObject conf = new JSONObject();
        conf.set("ticketNo", "EXP-001");
        conf.set("targetSystem", "superset");
        conf.set("dataset", "42");
        String sh = DsTaskScriptBuilder.biRefreshShell(n, conf);
        assertTrue(sh.contains("EXP-001"), sh);
        assertTrue(sh.contains("dataset/42/refresh") || sh.contains("$DATASET/refresh"), sh);
        assertEquals("SHELL", DsWorkflowBuilder.mapDsTaskType("sink_bi", "ds_sql", "DS_SQL"));
    }

    @Test
    void nosql_nodes_registered() {
        assertTrue(IgEtlNodeTypes.isKnown("source_mongo"));
        assertTrue(IgEtlNodeTypes.isKnown("source_hbase"));
        assertTrue(LhEtlDsCapability.allowsBind("mongodb", "source_mongo"));
        assertTrue(LhEtlDsCapability.allowsBind("hbase", "source_hbase"));
    }
}
