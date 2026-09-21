package vip.xiaonuo.lh.core.engine;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import org.junit.jupiter.api.Test;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlNode;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlinkIcebergSinkSqlCompilerTest {

    @Test
    void flink_sql_uses_backticks_jdbc_land_and_lake_catalog() {
        IgEtlNode n = new IgEtlNode();
        n.setNodeKey("n_mubax96vplut");
        JSONObject conf = new JSONObject();
        conf.set("catalog", "prod_catalog");
        conf.set("database", "log");
        conf.set("table", "ods_lakehouse_gov_dev_log");
        conf.set("_lhUpstreamTable", "lakehouse_gov.lh_ods_dev_log");
        conf.set("lhDatabase", "lakehouse_gov");
        conf.set("host", "127.0.0.1");
        conf.set("port", "7306");
        conf.set("dbType", "mysql");
        conf.set("warehouse", "s3://iceberg-warehouse/prod");
        conf.set("_lhEngine", "flink");
        JSONArray cols = new JSONArray();
        JSONObject id = new JSONObject();
        id.set("name", "ID");
        id.set("flinkType", "STRING");
        id.set("pk", true);
        cols.add(id);
        JSONObject name = new JSONObject();
        name.set("name", "NAME");
        name.set("flinkType", "STRING");
        cols.add(name);
        conf.set("_lhColumns", cols);

        String sql = FlinkIcebergSinkSqlCompiler.compile(n, conf);

        assertFalse(sql.contains("\""), sql);
        assertTrue(sql.contains("SET 'execution.runtime-mode' = 'BATCH'"), sql);
        assertTrue(sql.contains("'connector' = 'jdbc'"), sql);
        assertTrue(sql.contains("'table-name' = 'lh_ods_dev_log'"), sql);
        assertTrue(sql.contains("'catalog-name' = 'iceberg'"), sql);
        assertTrue(sql.contains("'catalog-database' = 'log'"), sql);
        assertTrue(sql.contains("'catalog-table' = 'ods_lakehouse_gov_dev_log'"), sql);
        assertTrue(sql.contains("'warehouse' = 's3a://warehouse/'"), sql);
        assertTrue(sql.contains("INSERT INTO `_lh_sink_ods_lakehouse_gov_dev_log` SELECT * FROM `_lh_src_lh_ods_dev_log`"), sql);
        assertTrue(sql.contains("`ID` STRING"), sql);
    }

    @Test
    void resolveSql_routes_flink_sink_iceberg() {
        IgEtlNode n = new IgEtlNode();
        n.setNodeKey("n_sink");
        n.setNodeType("sink_iceberg");
        JSONObject conf = new JSONObject();
        conf.set("_lhEngine", "flink");
        conf.set("catalog", "iceberg");
        conf.set("database", "ods");
        conf.set("table", "ods_demo");
        conf.set("_lhUpstreamTable", "lh_ods_demo");
        String sql = DsTaskScriptBuilder.resolveSql(n, conf);
        assertTrue(sql.contains("'connector' = 'iceberg'"), sql);
        assertFalse(sql.contains("prod_catalog"), sql);
        assertFalse(sql.contains("\""), sql);
    }
}
