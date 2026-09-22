package vip.xiaonuo.lh.modular.metric.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MetricMaterializeJobTemplateTest {

    @Test
    void workflowNameBindsMetricCode() {
        assertEquals("job.metric.mat.m-0001", MetricMaterializeJobTemplate.workflowName("M-0001"));
        assertEquals("job.metric.recon.m-0001", MetricMaterializeJobTemplate.reconWorkflowName("M-0001"));
    }

    @Test
    void icebergSqlContainsMetricCode() {
        String sql = MetricMaterializeJobTemplate.icebergInsertSql(
                "M-0001", "v3", "ads.metric_m_0001_d",
                "SELECT 1 AS metric_value, DATE '2026-09-22' AS dt", "2026-09-22");
        assertTrue(sql.contains("metric_code=M-0001"));
        assertTrue(sql.contains("INSERT INTO ads.metric_m_0001_d"));
    }

    @Test
    void ckSqlContainsMetricCode() {
        String sql = MetricMaterializeJobTemplate.clickHouseInsertSql(
                "M-0001", "v3", "ads.metric_m_0001_d",
                "SELECT 1 AS metric_value", "2026-09-22");
        assertTrue(sql.contains("engine=clickhouse"));
        assertTrue(sql.contains("INSERT INTO ads.metric_m_0001_d"));
    }

    @Test
    void reconSqlComparesLakeAndCk() {
        String sql = MetricMaterializeJobTemplate.reconCountSql(
                "iceberg.ads.metric_m_0001_d", "ads.metric_m_0001_d", "2026-09-22");
        assertTrue(sql.contains("UNION ALL"));
        assertTrue(sql.contains("iceberg.ads.metric_m_0001_d"));
        assertTrue(sql.contains("ads.metric_m_0001_d"));
    }

    @Test
    void rejectsUnsafeCode() {
        assertThrows(IllegalArgumentException.class,
                () -> MetricMaterializeJobTemplate.safeCode("bad;drop"));
    }
}
