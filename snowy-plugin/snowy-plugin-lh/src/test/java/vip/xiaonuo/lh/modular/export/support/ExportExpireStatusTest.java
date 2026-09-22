package vip.xiaonuo.lh.modular.export.support;

import org.junit.jupiter.api.Test;

import java.util.Calendar;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出湖到期/状态口径纯函数验收（不启 Spring）。
 */
class ExportExpireStatusTest {

    @Test
    void expiredTicketMapsToUrgent() {
        assertEquals("urgent", resolveUiStatus("expired", daysAgo(1)));
        assertEquals("urgent", resolveUiStatus("approved", daysAgo(1)));
        assertEquals("warn", resolveUiStatus("approved", daysFromNow(3)));
        assertEquals("ok", resolveUiStatus("approved", daysFromNow(30)));
        assertEquals("warn", resolveUiStatus("pending", null));
    }

    @Test
    void duePredicate() {
        assertTrue(isDue("approved", daysAgo(1), new Date()));
        assertTrue(!isDue("approved", daysFromNow(1), new Date()));
        assertTrue(!isDue("expired", daysAgo(1), new Date()));
        assertTrue(!isDue("pending", daysAgo(1), new Date()));
    }

    /** 与 ExportBoardServiceImpl.resolveUiStatus 对齐 */
    static String resolveUiStatus(String ticketStatus, Date expiresAt) {
        if ("pending".equals(ticketStatus)) {
            return "warn";
        }
        if ("expired".equals(ticketStatus)
                || "rejected".equals(ticketStatus)
                || "cancelled".equals(ticketStatus)) {
            return "urgent";
        }
        if (expiresAt != null) {
            Date now = new Date();
            if (expiresAt.before(now)) {
                return "urgent";
            }
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_MONTH, 7);
            if (!expiresAt.after(cal.getTime())) {
                return "warn";
            }
        }
        return "ok";
    }

    /** 与 ExportLifecycleServiceImpl 扫描条件对齐 */
    static boolean isDue(String status, Date expiresAt, Date now) {
        return "approved".equals(status) && expiresAt != null && !expiresAt.after(now);
    }

    private static Date daysAgo(int d) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, -d);
        return c.getTime();
    }

    private static Date daysFromNow(int d) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, d);
        return c.getTime();
    }
}
