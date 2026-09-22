package vip.xiaonuo.lh.modular.catalog.preview;

import org.junit.jupiter.api.Test;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;
import vip.xiaonuo.lh.modular.datasource.support.LhIcebergNamespaceNames;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcPreviewAdapterTableRefTest {

    @Test
    void postgres_bare_table_uses_public_not_database() throws Exception {
        LhDatasource ds = new LhDatasource();
        ds.setType("postgresql");
        ds.setDatabaseName("lakehouse_gov");
        Method m = JdbcPreviewAdapter.class.getDeclaredMethod("jdbcTableRef", String.class, LhDatasource.class);
        m.setAccessible(true);
        String ref = (String) m.invoke(null, "dev_log", ds);
        assertEquals("\"public\".\"dev_log\"", ref);

        String dotted = (String) m.invoke(null, "cp.dev_log", ds);
        assertEquals("\"cp\".\"dev_log\"", dotted);
    }

    @Test
    void resolve_matches_preview() {
        LhDatasource ds = new LhDatasource();
        ds.setType("postgresql");
        ds.setDatabaseName("lakehouse_gov");
        var st = LhIcebergNamespaceNames.resolveSchemaTable(ds, "dev_log");
        assertEquals("public", st.schema());
        assertTrue(st.table().equals("dev_log"));
    }
}
