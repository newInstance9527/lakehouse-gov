package vip.xiaonuo.lh.core.engine;

import cn.hutool.json.JSONObject;
import org.junit.jupiter.api.Test;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlNode;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataxJobBuilderPluginTest {

    @Test
    void sink_search_uses_elasticsearchwriter() {
        IgEtlNode n = new IgEtlNode();
        n.setNodeKey("n_es");
        n.setNodeType("sink_search");
        JSONObject conf = new JSONObject();
        conf.set("index", "evt_hot");
        conf.set("endpoint", "http://es:9200");
        conf.set("lhDsType", "elasticsearch");
        conf.set("lhUpstreamDsType", "mysql");
        Map<String, Object> job = DataxJobBuilder.buildJob(n, conf);
        @SuppressWarnings("unchecked")
        Map<String, Object> content0 = ((List<Map<String, Object>>) ((Map<?, ?>) job.get("job")).get("content")).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> writer = (Map<String, Object>) content0.get("writer");
        assertEquals("elasticsearchwriter", writer.get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> param = (Map<String, Object>) writer.get("parameter");
        assertEquals("evt_hot", param.get("index"));
        assertTrue(String.valueOf(param.get("endpoint")).contains("es")
                || String.valueOf(param.get("endpoint")).contains("LH_ES"), String.valueOf(param.get("endpoint")));
    }

    @Test
    void sink_object_s3_uses_osswriter_not_mysql() {
        IgEtlNode n = new IgEtlNode();
        n.setNodeKey("n_obj");
        n.setNodeType("sink_object");
        JSONObject conf = new JSONObject();
        conf.set("bucket", "raw");
        conf.set("basePath", "out/");
        conf.set("lhDsType", "s3");
        conf.set("lhUpstreamDsType", "mysql");
        Map<String, Object> job = DataxJobBuilder.buildJob(n, conf);
        @SuppressWarnings("unchecked")
        Map<String, Object> content0 = ((List<Map<String, Object>>) ((Map<?, ?>) job.get("job")).get("content")).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> writer = (Map<String, Object>) content0.get("writer");
        assertEquals("osswriter", writer.get("name"));
        assertFalse("mysqlwriter".equals(writer.get("name")));
    }

    @Test
    void sink_ftp_uses_ftpwriter() {
        IgEtlNode n = new IgEtlNode();
        n.setNodeKey("n_ftp");
        n.setNodeType("sink_ftp");
        JSONObject conf = new JSONObject();
        conf.set("host", "sftp.example.com");
        conf.set("remoteDir", "/out");
        conf.set("lhDsType", "ftp");
        conf.set("lhUpstreamDsType", "mysql");
        Map<String, Object> job = DataxJobBuilder.buildJob(n, conf);
        @SuppressWarnings("unchecked")
        Map<String, Object> content0 = ((List<Map<String, Object>>) ((Map<?, ?>) job.get("job")).get("content")).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> writer = (Map<String, Object>) content0.get("writer");
        assertEquals("ftpwriter", writer.get("name"));
    }

    @Test
    void source_api_uses_httpreader() {
        IgEtlNode n = new IgEtlNode();
        n.setNodeKey("n_api");
        n.setNodeType("source_api");
        JSONObject conf = new JSONObject();
        conf.set("baseUrl", "https://api.example.com/v1/items");
        conf.set("lhDsType", "http_api");
        Map<String, Object> job = DataxJobBuilder.buildJob(n, conf);
        @SuppressWarnings("unchecked")
        Map<String, Object> content0 = ((List<Map<String, Object>>) ((Map<?, ?>) job.get("job")).get("content")).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> reader = (Map<String, Object>) content0.get("reader");
        assertEquals("httpreader", reader.get("name"));
    }
}
