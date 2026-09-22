package vip.xiaonuo.lh.modular.lifecycle.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GovLcBucketMetricNamesTest {

    @Test
    void usedQueryPrefersPlatformThenMinio() {
        String q = GovLcBucketMetricNames.usedBytesQuery();
        assertTrue(q.startsWith(GovLcBucketMetricNames.USED_BYTES));
        assertTrue(q.contains(GovLcBucketMetricNames.MINIO_USED_V2));
        assertTrue(q.contains(GovLcBucketMetricNames.MINIO_USED_V3));
    }

    @Test
    void capacityQueryIncludesQuotaFallbacks() {
        String q = GovLcBucketMetricNames.capacityBytesQuery();
        assertTrue(q.contains(GovLcBucketMetricNames.CAPACITY_BYTES));
        assertTrue(q.contains(GovLcBucketMetricNames.MINIO_QUOTA_V2));
        assertTrue(q.contains(GovLcBucketMetricNames.MINIO_QUOTA_V3));
    }
}
