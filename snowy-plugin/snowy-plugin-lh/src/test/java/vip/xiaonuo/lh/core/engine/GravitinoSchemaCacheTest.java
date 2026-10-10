package vip.xiaonuo.lh.core.engine;

import org.junit.jupiter.api.Test;
import vip.xiaonuo.lh.config.LhProperties;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GravitinoSchemaCacheTest {

    @Test
    void putAndGetNamesWithinTtl() throws Exception {
        GravitinoSchemaCache cache = newCache(60_000);
        cache.putNames("schemas", "ml.cat", List.of("a", "b"));
        List<String> got = cache.getNames("schemas", "ml.cat");
        assertNotNull(got);
        assertEquals(List.of("a", "b"), got);
        assertTrue((Integer) cache.stats().get("live") >= 1);
    }

    @Test
    void putAndGetTable() throws Exception {
        GravitinoSchemaCache cache = newCache(60_000);
        GravitinoClient.GravTable t = new GravitinoClient.GravTable();
        t.metalake = "ml";
        t.catalog = "cat";
        t.schema = "sch";
        t.name = "tbl";
        GravitinoClient.GravColumn c = new GravitinoClient.GravColumn();
        c.name = "id";
        c.type = "bigint";
        t.columns.add(c);
        cache.putTable(t);
        GravitinoClient.GravTable got = cache.getTable("ml", "cat", "sch", "tbl");
        assertNotNull(got);
        assertEquals("memory_cache", got.cacheSource);
        assertEquals(1, got.columns.size());
        assertEquals("id", got.columns.get(0).name);
    }

    @Test
    void expiredNamesMiss() throws Exception {
        GravitinoSchemaCache cache = newCache(1);
        cache.putNames("catalogs", "ml", List.of("c1"));
        Thread.sleep(5);
        assertNull(cache.getNames("catalogs", "ml"));
    }

    private static GravitinoSchemaCache newCache(long ttlMs) throws Exception {
        GravitinoSchemaCache cache = new GravitinoSchemaCache();
        LhProperties props = new LhProperties();
        LhProperties.Gravitino g = new LhProperties.Gravitino();
        g.setSchemaCacheEnabled(true);
        g.setSchemaCacheTtlMs(ttlMs);
        g.setSchemaCacheFallbackWhenDown(true);
        props.setGravitino(g);
        Field f = GravitinoSchemaCache.class.getDeclaredField("lhProperties");
        f.setAccessible(true);
        f.set(cache, props);
        return cache;
    }
}
