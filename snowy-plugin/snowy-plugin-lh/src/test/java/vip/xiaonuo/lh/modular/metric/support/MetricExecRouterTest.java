package vip.xiaonuo.lh.modular.metric.support;

import org.junit.jupiter.api.Test;
import vip.xiaonuo.lh.modular.metric.entity.GovMetricMaterialize;

import static org.junit.jupiter.api.Assertions.*;

class MetricExecRouterTest {

    @Test
    void preferTrinoAlwaysTrino() {
        GovMetricMaterialize mat = okMat();
        MetricExecRouter.Decision d = MetricExecRouter.decide("trino", true, mat);
        assertFalse(d.useHot());
        assertEquals("trino", d.engine());
        assertNull(d.fallback());
    }

    @Test
    void hotWithoutCkFallsBack() {
        MetricExecRouter.Decision d = MetricExecRouter.decide("hot", false, okMat());
        assertFalse(d.useHot());
        assertEquals("trino", d.fallback());
        assertTrue(d.fallbackReason().contains("未配置"));
    }

    @Test
    void hotWithoutReconFallsBack() {
        GovMetricMaterialize mat = okMat();
        mat.setReconOk(0);
        MetricExecRouter.Decision d = MetricExecRouter.decide("hot", true, mat);
        assertFalse(d.useHot());
        assertTrue(d.fallbackReason().contains("对账"));
    }

    @Test
    void hotWithoutMaterializeFallsBack() {
        MetricExecRouter.Decision d = MetricExecRouter.decide("hot", true, null);
        assertFalse(d.useHot());
        assertTrue(d.fallbackReason().contains("物化登记"));
    }

    @Test
    void hotReadyUsesClickHouse() {
        MetricExecRouter.Decision d = MetricExecRouter.decide("hot", true, okMat());
        assertTrue(d.useHot());
        assertEquals("clickhouse", d.engine());
        assertEquals("clickhouse", d.dialect());
        assertNull(d.fallback());
        assertNotNull(d.materialize());
    }

    @Test
    void hotWithPartitionFailFallsBack() {
        MetricExecRouter.Decision d = MetricExecRouter.decide(
                "hot", true, okMat(), "分区对账失败 dt=2026-09-22 status=fail");
        assertFalse(d.useHot());
        assertTrue(d.fallbackReason().contains("分区对账失败"));
    }

    private static GovMetricMaterialize okMat() {
        GovMetricMaterialize m = new GovMetricMaterialize();
        m.setEngine("clickhouse");
        m.setTargetTable("ads.metric_m_0001_d");
        m.setReconOk(1);
        m.setStatus("active");
        return m;
    }
}
