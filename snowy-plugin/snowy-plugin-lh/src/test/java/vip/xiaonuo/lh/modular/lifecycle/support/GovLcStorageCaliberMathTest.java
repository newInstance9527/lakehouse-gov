package vip.xiaonuo.lh.modular.lifecycle.support;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GovLcStorageCaliberMathTest {

    @Test
    void activePlusReclaimableEqualsPhysical() {
        assertEquals(100L, GovLcStorageCaliberMath.physicalBytes(70, 30));
        assertTrue(GovLcStorageCaliberMath.holds(70, 30, 100));
        assertFalse(GovLcStorageCaliberMath.holds(70, 30, 99));
    }

    @Test
    void assertHoldsOnSummaryShape() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("activeBytes", 80L);
        row.put("reclaimableBytes", 20L);
        row.put("physicalBytes", 100L);
        assertDoesNotThrow(() -> GovLcStorageCaliberMath.assertHolds(row));
    }

    @Test
    void assertHoldsRejectsDrift() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("activeBytes", 80L);
        row.put("reclaimableBytes", 20L);
        row.put("totalBytes", 90L);
        assertThrows(IllegalStateException.class, () -> GovLcStorageCaliberMath.assertHolds(row));
    }
}
