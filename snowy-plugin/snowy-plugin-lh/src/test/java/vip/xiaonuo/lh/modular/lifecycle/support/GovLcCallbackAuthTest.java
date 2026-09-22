package vip.xiaonuo.lh.modular.lifecycle.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovLcCallbackAuthTest {

    @Test
    void tokenMatchesConstantTime() {
        assertTrue(GovLcCallbackAuth.tokenMatches("secret-a", "secret-a"));
        assertFalse(GovLcCallbackAuth.tokenMatches("secret-a", "secret-b"));
        assertFalse(GovLcCallbackAuth.tokenMatches("", "secret-a"));
        assertFalse(GovLcCallbackAuth.tokenMatches("secret-a", ""));
        assertFalse(GovLcCallbackAuth.tokenMatches(null, "x"));
    }

    @Test
    void mapDsStateAndNormalizeAlign() {
        assertEquals("success", GovLcDsLauncher.mapDsState("SUCCESS"));
        assertEquals("failed", GovLcDsLauncher.mapDsState("FAILURE"));
        assertEquals("running", GovLcDsLauncher.mapDsState("RUNNING_EXECUTION"));
    }
}
