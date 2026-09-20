package vip.xiaonuo.lh.modular.etl.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.xiaonuo.lh.modular.lineage.service.GovLineageService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IgEtlLineageSyncHelperTest {

    @Mock
    private GovLineageService govLineageService;

    @InjectMocks
    private IgEtlLineageSyncHelper helper;

    @Test
    void sync_ok() {
        when(govLineageService.syncFields(eq("default"), eq("dag.demo")))
                .thenReturn(Map.of("ok", true, "edgeCount", 3));
        Map<String, Object> r = helper.syncAfterDeploy("default", "dag.demo");
        assertTrue(Boolean.TRUE.equals(r.get("ok")));
        assertFalse(Boolean.TRUE.equals(r.get("degraded")));
        assertEquals(3, r.get("edgeCount"));
    }

    @Test
    void sync_softFail() {
        when(govLineageService.syncFields(eq("default"), isNull()))
                .thenThrow(new RuntimeException("mz down"));
        Map<String, Object> r = helper.syncAfterDeploy("default", null);
        assertFalse(Boolean.TRUE.equals(r.get("ok")));
        assertTrue(Boolean.TRUE.equals(r.get("degraded")));
    }
}
