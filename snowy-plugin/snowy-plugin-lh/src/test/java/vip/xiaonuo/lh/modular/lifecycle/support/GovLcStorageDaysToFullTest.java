package vip.xiaonuo.lh.modular.lifecycle.support;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovLcStorageDaysToFullTest {

    @Test
    void insufficientWhenFewerThan15Samples() {
        List<GovLcStorageDaysToFull.Point> pts = linearSeries(10, 1000, 100);
        GovLcStorageDaysToFull.Result r = GovLcStorageDaysToFull.compute(
                pts, List.of(), 1_000_000, 1900, 0, 0.7);
        assertFalse(r.available());
        assertEquals("INSUFFICIENT", r.reason());
        assertEquals(10, r.sampleCount());
    }

    @Test
    void changePointTruncatesHistory() {
        List<GovLcStorageDaysToFull.Point> pts = new ArrayList<>();
        // 前 20 天平缓，变更点后 20 天陡增
        for (int i = 0; i < 20; i++) {
            pts.add(new GovLcStorageDaysToFull.Point(i, 1000));
        }
        long cut = 19;
        for (int i = 20; i < 40; i++) {
            pts.add(new GovLcStorageDaysToFull.Point(i, 1000 + (i - 20) * 500L));
        }
        List<GovLcStorageDaysToFull.Point> window =
                GovLcStorageDaysToFull.segmentWindow(pts, List.of(cut));
        assertEquals(20, window.size());
        assertEquals(20, window.get(0).epochDay());
    }

    @Test
    void linearGrowthYieldsP50AndP95() {
        // 每天 +1000 bytes，30 天；容量留出明确 headroom
        List<GovLcStorageDaysToFull.Point> pts = linearSeries(30, 10_000, 1_000);
        long current = 10_000 + 29 * 1_000L;
        long capacity = current + 50_000;
        GovLcStorageDaysToFull.Result r = GovLcStorageDaysToFull.compute(
                pts, List.of(), capacity, current, 0, 0.7);
        assertTrue(r.available());
        assertEquals("OK", r.reason());
        assertNotNull(r.p50Days());
        assertNotNull(r.p95Days());
        assertEquals(50.0, r.p50Days(), 0.5);
        assertTrue(r.p95Days() <= r.p50Days() + 0.01);
        assertTrue(r.r2() > 0.99);
        assertFalse(r.unstable());
    }

    @Test
    void reclaimConfidenceShrinksRunway() {
        List<GovLcStorageDaysToFull.Point> pts = linearSeries(20, 0, 1_000);
        long current = 19_000;
        long capacity = 40_000;
        long reclaimable = 10_000;
        GovLcStorageDaysToFull.Result without = GovLcStorageDaysToFull.compute(
                pts, List.of(), capacity, current, 0, 0.7);
        GovLcStorageDaysToFull.Result with = GovLcStorageDaysToFull.compute(
                pts, List.of(), capacity, current, reclaimable, 0.7);
        assertTrue(with.p50Days() < without.p50Days());
        // headroom = 40000-19000-7000 = 14000 → 14 days at 1000/day
        assertEquals(14.0, with.p50Days(), 0.5);
    }

    @Test
    void nonPositiveSlopeSkips() {
        List<GovLcStorageDaysToFull.Point> pts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            pts.add(new GovLcStorageDaysToFull.Point(i, 10_000 - i * 10L));
        }
        GovLcStorageDaysToFull.Result r = GovLcStorageDaysToFull.compute(
                pts, List.of(), 100_000, 9_810, 0, 0.7);
        assertFalse(r.available());
        assertEquals("NON_POSITIVE_SLOPE", r.reason());
        assertNull(r.p50Days());
    }

    @Test
    void noCapacitySkips() {
        List<GovLcStorageDaysToFull.Point> pts = linearSeries(20, 0, 100);
        GovLcStorageDaysToFull.Result r = GovLcStorageDaysToFull.compute(
                pts, List.of(), 0, 1900, 0, 0.7);
        assertEquals("NO_CAPACITY", r.reason());
    }

    private static List<GovLcStorageDaysToFull.Point> linearSeries(int n, long base, long slope) {
        List<GovLcStorageDaysToFull.Point> pts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            pts.add(new GovLcStorageDaysToFull.Point(1000 + i, base + i * slope));
        }
        return pts;
    }
}
