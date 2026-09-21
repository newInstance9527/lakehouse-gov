package vip.xiaonuo.lh.modular.query.support;

import org.junit.jupiter.api.Test;
import vip.xiaonuo.lh.core.engine.TrinoClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CpQueryScanGuardTest {

    @Test
    void toTrinoDataSizeUsesAirliftLiteralsNotRawBytes() {
        assertEquals("10GB", CpQueryScanGuard.toTrinoDataSize(CpQueryScanGuard.ADHOC_DEFAULT_SCAN_BYTES));
        assertEquals("50GB", CpQueryScanGuard.toTrinoDataSize(CpQueryScanGuard.PLATFORM_HARD_SCAN_BYTES));
        assertEquals("0B", CpQueryScanGuard.toTrinoDataSize(0));
        assertEquals("1B", CpQueryScanGuard.toTrinoDataSize(1));
    }

    @Test
    void sessionHeaderSendsDataSizeNotIntegerBytes() {
        TrinoClient.ExecuteOptions opts = TrinoClient.ExecuteOptions.human("alice", 1000);
        opts.maxScanBytes = CpQueryScanGuard.ADHOC_DEFAULT_SCAN_BYTES;
        opts.sessionProps = CpQueryPrincipalMapper.sessionProps(null, "default", false, opts.maxScanBytes);
        assertEquals(
                "query_max_scan_physical_bytes=10GB,query_max_execution_time=10m",
                TrinoClient.buildSessionHeader(opts));

        opts.maxScanBytes = CpQueryScanGuard.PLATFORM_HARD_SCAN_BYTES;
        opts.sessionProps = CpQueryPrincipalMapper.sessionProps(null, "default", true, opts.maxScanBytes);
        assertEquals(
                "query_max_scan_physical_bytes=50GB,query_max_execution_time=30m",
                TrinoClient.buildSessionHeader(opts));
    }

    @Test
    void invalidSessionPropertyIsNotScanLimit() {
        String invalid = "Trino错误: query_max_scan_physical_bytes is invalid: 10737418240";
        assertTrue(CpQueryScanGuard.isInvalidSessionProperty(invalid));
        assertFalse(CpQueryScanGuard.isEngineScanLimitExceeded(invalid));
    }

    @Test
    void realScanOverLimitStaysScanLimit() {
        assertTrue(CpQueryScanGuard.isEngineScanLimitExceeded("扫描量超过 Trino session 限额 10737418240 字节，查询已取消"));
        assertTrue(CpQueryScanGuard.isEngineScanLimitExceeded(
                "Query exceeded maximum scan physical bytes limit of 10GB"));
        assertTrue(CpQueryScanGuard.isEngineScanLimitExceeded("Exceeded scan limit of 10GB"));
        assertFalse(CpQueryScanGuard.isInvalidSessionProperty(
                "Query exceeded maximum scan physical bytes limit of 10GB"));
    }
}
