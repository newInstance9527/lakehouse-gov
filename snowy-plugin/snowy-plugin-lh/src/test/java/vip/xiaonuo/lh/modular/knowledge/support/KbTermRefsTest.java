package vip.xiaonuo.lh.modular.knowledge.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KbTermRefsTest {

    @Test
    void normalizeTermAddsMetricLink() {
        String json = KbTermRefs.normalizeJson(
                "{\"metricCode\":\"gmv\",\"synonyms\":\"成交总额,GMV\",\"standardIds\":\"std1\"}",
                null,
                "term");
        assertTrue(json.contains("\"metricCode\":\"gmv\""));
        assertTrue(json.contains("/metric?code=gmv"));
        assertTrue(json.contains("成交总额"));
    }

    @Test
    void extractEdgesTyped() {
        String refs = KbTermRefs.normalizeJson(
                "{\"metricCode\":\"gmv\",\"assets\":[\"iceberg.db.t.c\"],\"related\":[\"e2\"]}",
                null,
                "term");
        List<KbTermRefs.EdgeDraft> edges = KbTermRefs.extractEdges("e1", refs, "default");
        assertEquals(3, edges.size());
        assertTrue(edges.stream().anyMatch(e -> KbTermRefs.EDGE_METRIC.equals(e.edgeType())));
        assertTrue(edges.stream().anyMatch(e -> KbTermRefs.EDGE_ASSET.equals(e.edgeType())));
        assertTrue(edges.stream().anyMatch(e -> KbTermRefs.EDGE_RELATED.equals(e.edgeType())));
    }
}
