package vip.xiaonuo.lh.modular.plat.service;

import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatOutboxServiceTest {

    @Test
    void backoffGrowsWithRetry() {
        Date t1 = PlatOutboxService.backoffAt(1, 30);
        Date t2 = PlatOutboxService.backoffAt(2, 30);
        Date t3 = PlatOutboxService.backoffAt(3, 30);
        long now = System.currentTimeMillis();
        assertTrue(t1.getTime() - now >= 25_000L);
        assertTrue(t2.getTime() > t1.getTime());
        assertTrue(t3.getTime() > t2.getTime());
    }
}
