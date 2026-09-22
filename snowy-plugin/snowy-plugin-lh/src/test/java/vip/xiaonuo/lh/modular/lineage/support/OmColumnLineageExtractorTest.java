package vip.xiaonuo.lh.modular.lineage.support;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OM columnsLineage → 门户字段边投影
 *
 * @author lakehouse
 * @date 2026/9/23
 */
class OmColumnLineageExtractorTest {

    @Test
    void splitColumnFqn_ok() {
        String[] p = OmColumnLineageExtractor.splitColumnFqn(
                "sample_data.ecommerce_db.shopify.dim_customer.customer_id");
        assertNotNull(p);
        assertEquals("sample_data.ecommerce_db.shopify.dim_customer", p[0]);
        assertEquals("customer_id", p[1]);
    }

    @Test
    void splitColumnFqn_blank() {
        assertNull(OmColumnLineageExtractor.splitColumnFqn(" "));
        assertNull(OmColumnLineageExtractor.splitColumnFqn("nodot"));
    }

    @Test
    void shortTableName_lastSegment() {
        assertEquals("dim_customer",
                OmColumnLineageExtractor.shortTableName("svc.db.schema.dim_customer"));
        assertEquals("plain", OmColumnLineageExtractor.shortTableName("plain"));
    }

    @Test
    void extractColumnEdges_fromUpstream() {
        Map<String, Object> data = Map.of(
                "upstreamEdges", List.of(
                        Map.of(
                                "fromEntity", "raw",
                                "toEntity", "dim",
                                "lineageDetails", Map.of(
                                        "sqlQuery", "SELECT id FROM raw",
                                        "columnsLineage", List.of(
                                                Map.of(
                                                        "fromColumns", List.of(
                                                                "sample_data.ecommerce_db.shopify.raw_customer.id"),
                                                        "toColumn",
                                                        "sample_data.ecommerce_db.shopify.dim_customer.customer_id"
                                                )
                                        )
                                )
                        )
                )
        );
        List<Map<String, String>> edges = OmColumnLineageExtractor.extractColumnEdges(data);
        assertEquals(1, edges.size());
        Map<String, String> e = edges.get(0);
        assertEquals("raw_customer", e.get("fromTable"));
        assertEquals("id", e.get("fromField"));
        assertEquals("dim_customer", e.get("toTable"));
        assertEquals("customer_id", e.get("toField"));
        assertTrue(e.get("omFromFqn").endsWith(".raw_customer.id"));
        assertEquals("om_column", OmColumnLineageExtractor.ETL_JOB_OM_COLUMN);
    }

    @Test
    void extractColumnEdges_dedupe() {
        Map<String, Object> col = Map.of(
                "fromColumns", List.of("a.b.t1.c1"),
                "toColumn", "a.b.t2.c2"
        );
        Map<String, Object> edge = Map.of("lineageDetails", Map.of("columnsLineage", List.of(col, col)));
        List<Map<String, String>> edges = OmColumnLineageExtractor.extractColumnEdges(
                Map.of("downstreamEdges", List.of(edge)));
        assertEquals(1, edges.size());
    }
}
