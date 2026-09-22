package vip.xiaonuo.lh.modular.metric.support;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MetricAnomalyCalcTest {

    @Test
    void extractScalarPrefersMetricValue() {
        BigDecimal v = MetricAnomalyCalc.extractScalar(
                List.of(Map.of("dt", "2026-09-22", "metric_value", 120)),
                List.of("dt", "metric_value"));
        assertEquals(0, BigDecimal.valueOf(120).compareTo(v));
    }

    @Test
    void changePctAndAnomaly() {
        BigDecimal pct = MetricAnomalyCalc.changePct(
                new BigDecimal("120"), new BigDecimal("100"));
        assertEquals(0, new BigDecimal("20.000000").compareTo(pct));
        assertTrue(MetricAnomalyCalc.isAnomaly(pct, new BigDecimal("20")));
        assertFalse(MetricAnomalyCalc.isAnomaly(pct, new BigDecimal("25")));
    }

    @Test
    void paramHashStable() {
        String a = MetricQueryCache.paramHash(Map.of("dt", "2026-09-22", "from", "a"), 100);
        String b = MetricQueryCache.paramHash(Map.of("from", "a", "dt", "2026-09-22"), 100);
        assertEquals(a, b);
        assertNotEquals(a, MetricQueryCache.paramHash(Map.of("dt", "2026-09-21"), 100));
    }
}
