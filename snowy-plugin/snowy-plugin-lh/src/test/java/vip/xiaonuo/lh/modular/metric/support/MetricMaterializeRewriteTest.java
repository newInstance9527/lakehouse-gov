package vip.xiaonuo.lh.modular.metric.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MetricMaterializeRewriteTest {

    @Test
    void rewriteClickHouseSingleDay() {
        String sql = MetricMaterializeRewrite.rewriteClickHouse(
                "ads.metric_m_0001_d", "近1天", List.of("dt"), null,
                "2026-09-20", "2026-09-14", "2026-09-20", 100);
        assertEquals(
                "SELECT * FROM ads.metric_m_0001_d WHERE dt = toDate('2026-09-20') LIMIT 100",
                sql);
        assertNull(MetricExecGuard.blockReason(sql));
    }

    @Test
    void rewriteClickHouseRange() {
        String sql = MetricMaterializeRewrite.rewriteClickHouse(
                "ads.metric_m_0001_d", "近7天", List.of("dt"), null,
                "2026-09-20", "2026-09-14", "2026-09-20", 50);
        assertTrue(sql.contains("BETWEEN toDate('2026-09-14') AND toDate('2026-09-20')"));
        assertTrue(sql.endsWith("LIMIT 50"));
    }

    @Test
    void rewriteTrinoUsesDateLiteral() {
        String sql = MetricMaterializeRewrite.rewriteTrino(
                "iceberg.ads.metric_m_0001", "近1天", List.of("dt"), "[\"dt\"]",
                "2026-09-20", "2026-09-14", "2026-09-20", 200);
        assertEquals(
                "SELECT * FROM iceberg.ads.metric_m_0001 WHERE dt = DATE '2026-09-20' LIMIT 200",
                sql);
    }

    @Test
    void rejectsUnsafeTable() {
        assertThrows(IllegalArgumentException.class, () ->
                MetricMaterializeRewrite.rewriteClickHouse(
                        "ads; DROP TABLE t", "近1天", List.of("dt"), null,
                        "2026-09-20", "2026-09-14", "2026-09-20", 10));
    }

    @Test
    void normalizeEngineAliases() {
        assertEquals("clickhouse", MetricMaterializeRewrite.normalizeEngine("ck"));
        assertEquals("clickhouse", MetricMaterializeRewrite.normalizeEngine("HOT"));
        assertEquals("iceberg", MetricMaterializeRewrite.normalizeEngine("ads"));
    }
}
