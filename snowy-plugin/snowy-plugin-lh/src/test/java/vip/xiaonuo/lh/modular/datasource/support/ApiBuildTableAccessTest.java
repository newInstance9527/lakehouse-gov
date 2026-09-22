package vip.xiaonuo.lh.modular.datasource.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiBuildTableAccessTest {

    @Test
    void objectNameMatches_short_and_qualified() {
        assertTrue(ApiBuildTableAccess.objectNameMatches("orders", "", "orders"));
        assertTrue(ApiBuildTableAccess.objectNameMatches("public.orders", "public", "orders"));
        assertTrue(ApiBuildTableAccess.objectNameMatches("iceberg.public.orders", "public", "orders"));
        assertTrue(ApiBuildTableAccess.objectNameMatches("ORDERS", "public", "orders"));
        assertFalse(ApiBuildTableAccess.objectNameMatches("public.orders", "other", "orders"));
        assertFalse(ApiBuildTableAccess.objectNameMatches("orders", "public", "items"));
    }

    @Test
    void extractTableRefs_from_join_and_quotes() {
        List<ApiBuildTableAccess.TableRef> refs = ApiBuildTableAccess.extractTableRefs(
                "SELECT a.id FROM public.orders a JOIN `ods`.`items` i ON a.id = i.oid");
        assertEquals(2, refs.size());
        assertEquals("public", refs.get(0).schema);
        assertEquals("orders", refs.get(0).table);
        assertEquals("ods", refs.get(1).schema);
        assertEquals("items", refs.get(1).table);
    }

    @Test
    void extractTableRefs_skips_dual_and_dedupes() {
        List<ApiBuildTableAccess.TableRef> dual = ApiBuildTableAccess.extractTableRefs("SELECT 1 FROM dual");
        assertTrue(dual.isEmpty());

        List<ApiBuildTableAccess.TableRef> dup = ApiBuildTableAccess.extractTableRefs(
                "SELECT * FROM t JOIN t ON 1=1");
        assertEquals(1, dup.size());
        assertEquals("t", dup.get(0).table);
    }
}
