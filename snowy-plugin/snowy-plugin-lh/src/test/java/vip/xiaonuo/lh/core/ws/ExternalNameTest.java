package vip.xiaonuo.lh.core.ws;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalNameTest {

    @Test
    void ofJoinsWsAndCode() {
        assertEquals("default__orders", ExternalName.of("default", "orders"));
        assertEquals("team-a__ds_mysql_1", ExternalName.of("team-a", "ds/mysql 1"));
    }

    @Test
    void ofDefaultsBlank() {
        assertEquals("default__unnamed", ExternalName.of(null, null));
        assertEquals("default__unnamed", ExternalName.of("  ", ""));
    }

    @Test
    void extIdPrefixesKind() {
        assertEquals("sqlrest_ds:default__orders",
                ExternalName.extId(ExternalName.KIND_SQLREST_DS, "default", "orders"));
        assertEquals("sqlrest_api:ws1__abc",
                ExternalName.extId(ExternalName.KIND_SQLREST_API, "ws1", "abc"));
        assertEquals("apisix:ws1__abc",
                ExternalName.extId(ExternalName.KIND_APISIX, "ws1", "abc"));
    }

    @Test
    void sameCodeDifferentWsDoNotCollide() {
        String a = ExternalName.of("ws_a", "gmv");
        String b = ExternalName.of("ws_b", "gmv");
        assertFalse(a.equals(b));
        assertTrue(ExternalName.extId(ExternalName.KIND_SQLREST_API, "ws_a", "gmv")
                .startsWith("sqlrest_api:ws_a__"));
    }

    @Test
    void marquezNamespacePerWs() {
        assertEquals("default__lakehouse", ExternalName.marquezNamespace("default"));
        assertEquals("team_x__lakehouse", ExternalName.marquezNamespace("team_x"));
        assertEquals("dag1.node_a", ExternalName.marquezJob("dag1", "node_a"));
    }
}
