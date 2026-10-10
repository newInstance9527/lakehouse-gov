package vip.xiaonuo.lh.modular.recon.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovReconCallbackAuthTest {

    @Test
    void tokenMatches() {
        assertTrue(GovReconCallbackAuth.tokenMatches("secret-a", "secret-a"));
        assertFalse(GovReconCallbackAuth.tokenMatches("secret-a", "secret-b"));
        assertFalse(GovReconCallbackAuth.tokenMatches("", "secret-a"));
        assertFalse(GovReconCallbackAuth.tokenMatches("secret-a", ""));
        assertFalse(GovReconCallbackAuth.tokenMatches(null, "x"));
    }
}
