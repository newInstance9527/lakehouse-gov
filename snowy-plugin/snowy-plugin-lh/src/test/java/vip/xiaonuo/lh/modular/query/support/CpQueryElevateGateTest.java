package vip.xiaonuo.lh.modular.query.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CpQueryElevateGateTest {

    @Test
    void denyPayloadMarksElevateDenied() {
        var m = CpQueryElevateGate.deniedPayload();
        assertEquals(Boolean.TRUE, m.get("blocked"));
        assertEquals(CpQueryElevateGate.ERROR_CODE, m.get("errorCode"));
        assertEquals(CpQueryElevateGate.APPLY_PATH, m.get("applyPath"));
        assertEquals(Boolean.TRUE, m.get("applyHint"));
        assertFalse(Boolean.TRUE.equals(m.get("elevated")));
        assertTrue(String.valueOf(m.get("message")).contains("50GB"));
    }

    @Test
    void constantsStable() {
        assertEquals("scan_elevate", CpQueryElevateGate.TICKET_TYPE);
        assertEquals("SCAN_ELEVATE", CpQueryElevateGate.PRIVILEGE);
        assertEquals("adhoc", CpQueryElevateGate.RESOURCE_TYPE);
        assertEquals("platform", CpQueryElevateGate.RESOURCE_ID);
    }
}
