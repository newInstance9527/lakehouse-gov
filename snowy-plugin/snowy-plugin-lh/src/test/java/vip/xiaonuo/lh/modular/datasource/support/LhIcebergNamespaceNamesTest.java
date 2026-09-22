package vip.xiaonuo.lh.modular.datasource.support;

import org.junit.jupiter.api.Test;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LhIcebergNamespaceNamesTest {

    @Test
    void database_and_summary_namespaces() {
        LhDatasource ds = new LhDatasource();
        ds.setDatabaseName("hms");
        ds.setSchemaSummary("ods_trade, dwd_trade, dws_trade, ads");
        assertEquals(List.of("hms", "ods_trade", "dwd_trade", "dws_trade", "ads"),
                LhIcebergNamespaceNames.resolve(ds, List.of()));
    }

    @Test
    void extracts_schema_from_table_and_fqn() {
        LhDatasource ds = new LhDatasource();
        ds.setSchemaSummary("ods_trade.s_order\niceberg.dwd_trade.dwd_order");
        List<String> names = LhIcebergNamespaceNames.resolve(ds, List.of("ads.gmv", "s3a://warehouse/skip"));
        assertEquals(List.of("ods_trade", "dwd_trade", "ads"), names);
    }

    @Test
    void parseSchemaTable_prefers_object_name_over_hms_placeholder() {
        var st = LhIcebergNamespaceNames.parseSchemaTable("log.ods_lakehouse_gov_dev_log", "hms");
        assertEquals("log", st.schema());
        assertEquals("ods_lakehouse_gov_dev_log", st.table());

        var fqn = LhIcebergNamespaceNames.parseSchemaTable("iceberg.log.ods_x", "hms");
        assertEquals("log", fqn.schema());
        assertEquals("ods_x", fqn.table());

        var bare = LhIcebergNamespaceNames.parseSchemaTable("ods_only", "log");
        assertEquals("log", bare.schema());
        assertEquals("ods_only", bare.table());
    }

    @Test
    void resolveSchemaTable_postgres_defaults_public_not_database() {
        LhDatasource pg = new LhDatasource();
        pg.setType("postgresql");
        pg.setDatabaseName("lakehouse_gov");
        var bare = LhIcebergNamespaceNames.resolveSchemaTable(pg, "dev_log");
        assertEquals("public", bare.schema());
        assertEquals("dev_log", bare.table());

        var dotted = LhIcebergNamespaceNames.resolveSchemaTable(pg, "cp.dev_log");
        assertEquals("cp", dotted.schema());
        assertEquals("dev_log", dotted.table());

        LhDatasource mysql = new LhDatasource();
        mysql.setType("mysql");
        mysql.setDatabaseName("lakehouse_gov");
        var m = LhIcebergNamespaceNames.resolveSchemaTable(mysql, "dev_log");
        assertEquals("lakehouse_gov", m.schema());
        assertEquals("dev_log", m.table());
    }

    @Test
    void skips_invalid_and_system() {
        LhDatasource ds = new LhDatasource();
        ds.setDatabaseName("information_schema");
        ds.setSchemaSummary("sys, 1bad, ods-trade, /path");
        assertTrue(LhIcebergNamespaceNames.resolve(ds, null).isEmpty());
    }
}
