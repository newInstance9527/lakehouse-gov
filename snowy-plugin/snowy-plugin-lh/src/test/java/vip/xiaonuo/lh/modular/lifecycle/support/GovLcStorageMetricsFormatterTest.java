package vip.xiaonuo.lh.modular.lifecycle.support;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovLcStorageMetricsFormatterTest {

    @Test
    void dayCutAlignsToUtcMidnight() {
        Instant noon = Instant.parse("2026-09-23T12:34:56Z");
        assertEquals(Instant.parse("2026-09-23T00:00:00Z").toEpochMilli(),
                GovLcStorageMetricsFormatter.dayCutEpochMs(noon));
    }

    @Test
    void formatsThreeCaliberGaugesWithOwnerLabel() {
        long ts = Instant.parse("2026-09-23T00:00:00Z").toEpochMilli();
        List<String> lines = GovLcStorageMetricsFormatter.format(
                new GovLcStorageMetricsFormatter.Sample(
                        "ods_trade.s_order", "default", "ODS", "alice",
                        1000, 1500, 500, 10, 100, 0.25, 4, 2),
                ts);
        String body = GovLcStorageMetricsFormatter.joinBody(lines);
        assertTrue(body.contains("lh_table_storage_bytes{fqtn=\"ods_trade.s_order\",ws=\"default\",layer=\"ODS\",owner=\"alice\",kind=\"active\"} 1000 " + ts));
        assertTrue(body.contains("kind=\"total\"} 1500 "));
        assertTrue(body.contains("kind=\"reclaimable\"} 500 "));
        assertTrue(body.contains("lh_table_storage_snapshots{"));
        assertTrue(body.contains("lh_table_storage_partitions{"));
        assertTrue(body.contains("0.250000"));
        assertFalse(body.contains("Pushgateway"));
    }

    @Test
    void blankOwnerBecomesUnassigned() {
        long ts = Instant.parse("2026-09-23T00:00:00Z").toEpochMilli();
        List<String> lines = GovLcStorageMetricsFormatter.format(
                new GovLcStorageMetricsFormatter.Sample(
                        "t1", "default", "DWD", null,
                        1, 1, 0, 1, 1, 0, null, null),
                ts);
        assertTrue(GovLcStorageMetricsFormatter.joinBody(lines).contains("owner=\"unassigned\""));
    }

    @Test
    void formatsDaysToFullQuantiles() {
        long ts = Instant.parse("2026-09-23T00:00:00Z").toEpochMilli();
        List<String> lines = GovLcStorageMetricsFormatter.formatDaysToFull(
                "ods_trade.s_order", "default", "ODS", "bob", 62.5, 48.0, ts);
        String body = GovLcStorageMetricsFormatter.joinBody(lines);
        assertTrue(body.contains("lh_table_storage_days_to_full{fqtn=\"ods_trade.s_order\",ws=\"default\",layer=\"ODS\",owner=\"bob\",quantile=\"p50\"} 62.500000 " + ts));
        assertTrue(body.contains("quantile=\"p95\"} 48 "));
        assertFalse(body.contains("cursor"));
    }

    @Test
    void formatsWsStorageUsedAndQuota() {
        long ts = Instant.parse("2026-09-23T00:00:00Z").toEpochMilli();
        List<String> lines = GovLcStorageMetricsFormatter.formatWsStorage(
                "default", "ws-owner", 100L, 1000L, ts);
        String body = GovLcStorageMetricsFormatter.joinBody(lines);
        assertTrue(body.contains("lh_ws_storage_used_bytes{ws=\"default\",owner=\"ws-owner\"} 100 " + ts));
        assertTrue(body.contains("lh_ws_storage_quota_bytes{ws=\"default\",owner=\"ws-owner\"} 1000 " + ts));
    }

    @Test
    void escapesLabelQuotes() {
        assertEquals("a\\\"b", GovLcStorageMetricsFormatter.esc("a\"b"));
    }

    @Test
    void partitionsSqlPresent() {
        GovLcMetadataSql.TableRef ref = GovLcMetadataSql.parse("ods.t1", "iceberg");
        assertTrue(GovLcMetadataSql.partitions(ref).contains("\"t1$partitions\""));
    }
}
