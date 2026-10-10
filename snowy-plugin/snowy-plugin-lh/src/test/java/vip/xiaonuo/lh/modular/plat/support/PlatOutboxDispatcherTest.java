package vip.xiaonuo.lh.modular.plat.support;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import vip.xiaonuo.lh.modular.plat.entity.PlatOutboxEvent;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatOutboxDispatcherTest {

    @Test
    void prefersLowerOrderHandler() throws Exception {
        AtomicBoolean specific = new AtomicBoolean(false);
        AtomicBoolean drain = new AtomicBoolean(false);

        PlatOutboxHandler specificHandler = new PlatOutboxHandler() {
            @Override
            public boolean supports(String eventType) {
                return eventType != null && eventType.startsWith("quality.");
            }

            @Override
            public int order() {
                return 10;
            }

            @Override
            public void handle(PlatOutboxEvent event) {
                specific.set(true);
            }
        };
        PlatOutboxHandler drainHandler = new PlatOutboxHandler() {
            @Override
            public boolean supports(String eventType) {
                return true;
            }

            @Override
            public int order() {
                return 1000;
            }

            @Override
            public void handle(PlatOutboxEvent event) {
                drain.set(true);
            }
        };

        PlatOutboxDispatcher d = new PlatOutboxDispatcher();
        ReflectionTestUtils.setField(d, "handlers", List.of(drainHandler, specificHandler));

        PlatOutboxEvent ev = new PlatOutboxEvent();
        ev.setEventType("quality.gate.blocked");
        d.dispatch(ev);

        assertTrue(specific.get());
        assertTrue(!drain.get());
        assertEquals(10, d.resolve("quality.gate.blocked").order());
        assertEquals(1000, d.resolve("recon.golden.delist").order());
    }
}
