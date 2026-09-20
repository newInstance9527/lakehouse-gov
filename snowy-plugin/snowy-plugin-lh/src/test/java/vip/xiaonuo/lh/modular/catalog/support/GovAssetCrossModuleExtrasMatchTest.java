package vip.xiaonuo.lh.modular.catalog.support;

import org.junit.jupiter.api.Test;
import vip.xiaonuo.lh.modular.quality.entity.GovDqRule;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovAssetCrossModuleExtrasMatchTest {

    @Test
    void matches_by_assetId() {
        GovDqRule r = new GovDqRule();
        r.setAssetId("aid-1");
        r.setTableName("other");
        assertTrue(GovAssetCrossModuleExtras.matches(r, "aid-1", "x", null, null));
        assertFalse(GovAssetCrossModuleExtras.matches(r, "aid-2", "x", null, null));
    }

    @Test
    void matches_by_tableName_exact_or_short() {
        GovDqRule r = new GovDqRule();
        r.setTableName("dwd_trade.dwd_order");
        assertTrue(GovAssetCrossModuleExtras.matches(r, null, "dwd_trade.dwd_order", null, "dwd_order"));
        assertTrue(GovAssetCrossModuleExtras.matches(r, null, "dwd_order", null, "dwd_order"));
        assertFalse(GovAssetCrossModuleExtras.matches(r, null, "dwd_user", null, "dwd_user"));
    }

    @Test
    void matches_by_omFqn() {
        GovDqRule r = new GovDqRule();
        r.setTableName("ads.ads_gmv");
        assertTrue(GovAssetCrossModuleExtras.matches(r, null, null, "svc.ads.ads_gmv", "ads_gmv"));
    }
}
