package vip.xiaonuo.lh.modular.metric.support;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class GovMetricBindLookupTest {

    @Test
    void tableMatchesShortAndFqn() {
        List<String> keys = GovMetricBindLookup.tableKeys(
                "dwd_trade.dwd_order_detail", "dwd_order_detail", null);
        Assertions.assertTrue(GovMetricBindLookup.tableMatches("dwd_trade.dwd_order_detail", keys));
        Assertions.assertTrue(GovMetricBindLookup.tableMatches("dwd_order_detail", keys));
        Assertions.assertFalse(GovMetricBindLookup.tableMatches("ads.other", keys));
    }

    @Test
    void pinnedVerFromSourceRef() {
        Assertions.assertEquals("v3", GovMetricBindLookup.pinnedVer("M-0001@v3"));
        Assertions.assertNull(GovMetricBindLookup.pinnedVer("M-0001"));
        Assertions.assertNull(GovMetricBindLookup.pinnedVer(null));
    }
}
