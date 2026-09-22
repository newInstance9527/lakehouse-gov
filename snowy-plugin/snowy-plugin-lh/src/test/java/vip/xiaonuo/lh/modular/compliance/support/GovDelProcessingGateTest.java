package vip.xiaonuo.lh.modular.compliance.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovDelProcessingGateTest {

    @Test
    void tableTailStripsCatalog() {
        assertEquals("ods_trade.s_order", GovDelProcessingGate.tableTail("prod_catalog.ods_trade.s_order"));
        assertEquals("ods_trade.s_order", GovDelProcessingGate.tableTail("ods_trade.s_order"));
        assertEquals("s_order", GovDelProcessingGate.tableTail("s_order"));
    }

    @Test
    void tablesMatchAcrossCatalog() {
        assertTrue(GovDelProcessingGate.tablesMatch(
                "prod_catalog.ods_trade.s_order", "ods_trade.s_order"));
        assertTrue(GovDelProcessingGate.tablesMatch("ods_trade.s_order", "ODS_TRADE.S_ORDER"));
        assertFalse(GovDelProcessingGate.tablesMatch("ods_trade.s_order", "dwd_user.dwd_user_info"));
    }

    @Test
    void scopeExactMatchIgnoresSpaces() {
        assertTrue(GovDelProcessingGate.scopeMatches(
                "dt = 2026-08", "dt=2026-08", "dt", "2026-08"));
        assertFalse(GovDelProcessingGate.scopeMatches(
                "dt>=2023-01", "dt=2026-08", "dt", "2026-08"));
    }

    @Test
    void anyTableMatchUsesTail() {
        assertTrue(GovDelProcessingGate.anyTableMatch(
                List.of("iceberg.ods_trade.s_order"), "ods_trade.s_order"));
        assertFalse(GovDelProcessingGate.anyTableMatch(
                List.of("ads.ads_gmv"), "ods_trade.s_order"));
    }

    @Test
    void compactScopeNormalizesEquals() {
        assertEquals("dt=2026-08", GovDelProcessingGate.compactScope(" dt = 2026-08 "));
    }
}
