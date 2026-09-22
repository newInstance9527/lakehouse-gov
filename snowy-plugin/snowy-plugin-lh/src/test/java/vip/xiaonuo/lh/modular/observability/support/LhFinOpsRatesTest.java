package vip.xiaonuo.lh.modular.observability.support;

import org.junit.jupiter.api.Test;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.modular.lifecycle.support.GovLcStorageEventCollect;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LhFinOpsRatesTest {

    @Test
    void storageCostOneTbOneMonth() {
        BigDecimal cost = LhFinOpsRates.storageCost(LhFinOpsRates.TB, 30, new BigDecimal("100"));
        assertEquals(new BigDecimal("100.00"), cost);
    }

    @Test
    void storageCostHalfTbSevenDays() {
        // 0.5 TB × 100 × (7/30) ≈ 11.67
        BigDecimal cost = LhFinOpsRates.storageCost(LhFinOpsRates.TB / 2, 7, new BigDecimal("100"));
        assertEquals(new BigDecimal("11.67"), cost);
    }

    @Test
    void computeCostTwoGb() {
        BigDecimal cost = LhFinOpsRates.computeCost(2 * LhFinOpsRates.GB, new BigDecimal("0.50"));
        assertEquals(new BigDecimal("1.00"), cost);
    }

    @Test
    void ratesMapFromProperties() {
        LhProperties props = new LhProperties();
        props.getFinops().setStoragePerTbMonth(new BigDecimal("120"));
        props.getFinops().setComputePerGbScan(new BigDecimal("0.80"));
        props.getFinops().setCurrency("CNY");
        var rates = LhFinOpsRates.ratesMap(props);
        assertEquals(new BigDecimal("120"), rates.get("storagePerTbMonth"));
        assertEquals(new BigDecimal("0.80"), rates.get("computePerGbScan"));
        assertEquals("CNY", rates.get("currency"));
        assertTrue(String.valueOf(rates.get("source")).contains("24.3"));
    }

    @Test
    void formatCny() {
        assertEquals("¥0.00", LhFinOpsRates.formatCny(null));
        assertEquals("¥12.30", LhFinOpsRates.formatCny(new BigDecimal("12.3")));
    }
}

class GovLcStorageEventCollectGateTest {

    @Test
    void onlyL3Accepted() {
        assertTrue(GovLcStorageEventCollect.isL3("L3"));
        assertTrue(GovLcStorageEventCollect.isL3("l3"));
        assertFalse(GovLcStorageEventCollect.isL3("L1"));
        assertFalse(GovLcStorageEventCollect.isL3("L2"));
        assertFalse(GovLcStorageEventCollect.isL3(null));
        assertFalse(GovLcStorageEventCollect.isL3(""));
    }
}
