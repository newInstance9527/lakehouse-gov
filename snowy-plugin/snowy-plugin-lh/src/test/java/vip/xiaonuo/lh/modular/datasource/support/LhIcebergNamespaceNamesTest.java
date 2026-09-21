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
    void skips_invalid_and_system() {
        LhDatasource ds = new LhDatasource();
        ds.setDatabaseName("information_schema");
        ds.setSchemaSummary("sys, 1bad, ods-trade, /path");
        assertTrue(LhIcebergNamespaceNames.resolve(ds, null).isEmpty());
    }
}
