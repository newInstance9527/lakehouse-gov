package vip.xiaonuo.lh.modular.quality.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovDqRuleProbeServicePortalDsTest {

    @Test
    void portalDsCatalogShape() {
        assertTrue(GovDqRuleProbeService.isPortalDsCatalog("ds_2100916266170396672"));
        assertTrue(GovDqRuleProbeService.isPortalDsCatalog("DS_abc"));
        assertFalse(GovDqRuleProbeService.isPortalDsCatalog("iceberg"));
        assertFalse(GovDqRuleProbeService.isPortalDsCatalog("hive"));
        assertFalse(GovDqRuleProbeService.isPortalDsCatalog(null));
        assertFalse(GovDqRuleProbeService.isPortalDsCatalog("ods_trade"));
    }
}
