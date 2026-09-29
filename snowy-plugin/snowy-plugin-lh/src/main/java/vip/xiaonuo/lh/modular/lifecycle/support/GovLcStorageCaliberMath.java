package vip.xiaonuo.lh.modular.lifecycle.support;

import java.util.Map;

/**
 * 三口径恒等式：active + reclaimable = physical（total）。
 * 供 storage API 与单测共用，避免两页口径漂移。
 */
public final class GovLcStorageCaliberMath {

    private GovLcStorageCaliberMath() {
    }

    public static long physicalBytes(long activeBytes, long reclaimableBytes) {
        return Math.max(0L, activeBytes) + Math.max(0L, reclaimableBytes);
    }

    public static boolean holds(long activeBytes, long reclaimableBytes, long physicalBytes) {
        return physicalBytes(activeBytes, reclaimableBytes) == physicalBytes;
    }

    public static void assertHolds(Map<String, Object> summaryOrRow) {
        if (summaryOrRow == null) {
            throw new IllegalArgumentException("row null");
        }
        long active = asLong(summaryOrRow.get("activeBytes"));
        long reclaim = asLong(summaryOrRow.get("reclaimableBytes"));
        long physical = asLong(first(summaryOrRow, "physicalBytes", "totalBytes"));
        if (!holds(active, reclaim, physical)) {
            throw new IllegalStateException(
                    "caliber broken: active(" + active + ")+reclaimable(" + reclaim
                            + ")!=physical(" + physical + ")");
        }
    }

    private static Object first(Map<String, Object> m, String a, String b) {
        Object v = m.get(a);
        return v != null ? v : m.get(b);
    }

    private static long asLong(Object v) {
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(String.valueOf(v).trim());
    }
}
