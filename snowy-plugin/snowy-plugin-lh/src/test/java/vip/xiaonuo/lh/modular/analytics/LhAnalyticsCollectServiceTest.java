package vip.xiaonuo.lh.modular.analytics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.modular.analytics.param.LhAnalyticsCollectParam;
import vip.xiaonuo.lh.modular.analytics.service.impl.LhAnalyticsCollectServiceImpl;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LhAnalyticsCollectServiceTest {

    @Mock
    private LhProperties lhProperties;

    @InjectMocks
    private LhAnalyticsCollectServiceImpl service;

    @Test
    void rejectsForeignAppKey() {
        LhProperties.Analytics cfg = new LhProperties.Analytics();
        cfg.setAppKey("lakehouse-portal");
        when(lhProperties.getAnalytics()).thenReturn(cfg);

        LhAnalyticsCollectParam param = new LhAnalyticsCollectParam();
        param.setAppKey("evil-app");
        param.setEvents(List.of(Map.of("eventName", "page_view")));

        assertThrows(CommonException.class, () -> service.collect(param));
    }

    @Test
    void skipsWhenDisabled() {
        LhProperties.Analytics cfg = new LhProperties.Analytics();
        cfg.setEnabled(false);
        cfg.setAppKey("lakehouse-portal");
        when(lhProperties.getAnalytics()).thenReturn(cfg);

        LhAnalyticsCollectParam param = new LhAnalyticsCollectParam();
        param.setAppKey("lakehouse-portal");
        param.setEvents(List.of(Map.of("eventId", "e1", "eventName", "page_view")));

        Map<String, Object> out = service.collect(param);
        assertTrue(Boolean.TRUE.equals(out.get("ok")));
        assertTrue(Boolean.TRUE.equals(out.get("skipped")));
        assertEquals("disabled", out.get("reason"));
        assertEquals(1, out.get("accepted"));
    }
}
