package vip.xiaonuo.lh.modular.ai.support;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiSchemaContextAclTest {

    @Test
    void normalize_keepsAssetIdAndColumns() {
        List<Map<String, Object>> raw = List.of(
                Map.of(
                        "assetId", "a1",
                        "schema", "public",
                        "table", "orders",
                        "columns", List.of(Map.of("name", "id", "type", "bigint"))));
        List<Map<String, Object>> out = AiSchemaContextAcl.normalize(raw);
        assertEquals(1, out.size());
        assertEquals("a1", out.get(0).get("assetId"));
        assertEquals("public", out.get(0).get("schema"));
        assertEquals("orders", out.get(0).get("table"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cols = (List<Map<String, Object>>) out.get(0).get("columns");
        assertEquals(1, cols.size());
        assertEquals("id", cols.get(0).get("name"));
    }

    @Test
    void normalize_parsesFqnWhenTableMissing() {
        List<Map<String, Object>> out = AiSchemaContextAcl.normalize(List.of(
                Map.of("fqn", "iceberg.ods.s_order")));
        assertEquals(1, out.size());
        assertEquals("ods", out.get(0).get("schema"));
        assertEquals("s_order", out.get(0).get("table"));
    }

    @Test
    void normalize_emptyAndBlankSkipped() {
        assertTrue(AiSchemaContextAcl.normalize(null).isEmpty());
        assertTrue(AiSchemaContextAcl.normalize(List.of()).isEmpty());
        assertTrue(AiSchemaContextAcl.normalize(List.of(Map.of("schema", "x"))).isEmpty());
    }

    @Test
    void matchesTableRef_objectNameAndAssetCode() {
        assertTrue(AiSchemaContextAcl.matchesTableRef("public", "orders", "public.orders"));
        assertTrue(AiSchemaContextAcl.matchesTableRef("public", "orders", "iceberg.public.orders"));
        assertTrue(AiSchemaContextAcl.matchesTableRef("", "orders", "orders"));
        assertFalse(AiSchemaContextAcl.matchesTableRef("public", "orders", "public.items"));
    }

    @Test
    void retainReadable_softStripsUnauthorized() {
        List<Map<String, Object>> normalized = AiSchemaContextAcl.normalize(List.of(
                Map.of("assetId", "owned1", "schema", "s", "table", "t1", "columns", List.of()),
                Map.of("assetId", "denied", "schema", "s", "table", "t2", "columns", List.of()),
                Map.of("schema", "s", "table", "orphan", "columns", List.of())));
        Set<String> allowed = new HashSet<>(Set.of("owned1"));
        int[] dropped = new int[1];
        List<Map<String, Object>> kept = AiSchemaContextAcl.retainReadable(
                normalized,
                AiSchemaContextAcl::firstAssetId,
                allowed::contains,
                dropped);
        assertEquals(1, kept.size());
        assertEquals("owned1", kept.get(0).get("assetId"));
        assertEquals(2, dropped[0]);
    }

    @Test
    void retainReadable_emptyPassthrough() {
        int[] dropped = new int[1];
        List<Map<String, Object>> kept = AiSchemaContextAcl.retainReadable(
                List.of(), m -> "x", id -> true, dropped);
        assertTrue(kept.isEmpty());
        assertEquals(0, dropped[0]);
    }
}
