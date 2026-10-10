package vip.xiaonuo.lh.modular.dataapi.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataapiRuntimeAuthTest {

    @Test
    void tokenMatches() {
        assertTrue(DataapiRuntimeAuth.tokenMatches("sk_abc", "sk_abc"));
        assertFalse(DataapiRuntimeAuth.tokenMatches("sk_abc", "sk_xyz"));
        assertFalse(DataapiRuntimeAuth.tokenMatches("", "sk_abc"));
        assertFalse(DataapiRuntimeAuth.tokenMatches("sk_abc", ""));
        assertFalse(DataapiRuntimeAuth.tokenMatches(null, "x"));
    }

    @Test
    void stripBearer() {
        assertEquals("tok", DataapiRuntimeAuth.stripBearer("Bearer tok"));
        assertEquals("tok", DataapiRuntimeAuth.stripBearer("bearer tok"));
        assertEquals("tok", DataapiRuntimeAuth.stripBearer("tok"));
        assertNull(DataapiRuntimeAuth.stripBearer(""));
        assertNull(DataapiRuntimeAuth.stripBearer(null));
    }

    @Test
    void normalizePath() {
        assertEquals("/api/demo", DataapiRuntimeAuth.normalizePath("api/demo"));
        assertEquals("/api/demo", DataapiRuntimeAuth.normalizePath("/api/demo/"));
        assertEquals("/api/demo", DataapiRuntimeAuth.normalizePath("/api/demo"));
        assertEquals("", DataapiRuntimeAuth.normalizePath(""));
    }
}
