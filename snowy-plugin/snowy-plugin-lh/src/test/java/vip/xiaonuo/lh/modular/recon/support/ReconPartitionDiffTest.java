package vip.xiaonuo.lh.modular.recon.support;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 纯度量计算（不启 Spring）：差异比例与阈值裁决。
 */
class ReconPartitionDiffTest {

    @Test
    void passWhenWithinThreshold() {
        assertEquals("pass", decide(1000, 1000, "0.001"));
        assertEquals("pass", decide(1000, 999, "0.002"));
    }

    @Test
    void failWhenAboveThreshold() {
        assertEquals("fail", decide(1000, 990, "0.001"));
    }

    @Test
    void skippedWhenMissingRows() {
        assertEquals("skipped", decide(-1, 10, "0.001"));
    }

    private static String decide(long lakeRows, long ckRows, String thresholdStr) {
        BigDecimal threshold = new BigDecimal(thresholdStr);
        if (lakeRows < 0 || ckRows < 0) {
            return "skipped";
        }
        if (lakeRows == 0 && ckRows == 0) {
            return "pass";
        }
        long base = Math.max(lakeRows, 1);
        BigDecimal diff = BigDecimal.valueOf(Math.abs(lakeRows - ckRows))
                .divide(BigDecimal.valueOf(base), 8, java.math.RoundingMode.HALF_UP);
        return diff.compareTo(threshold) > 0 ? "fail" : "pass";
    }
}
