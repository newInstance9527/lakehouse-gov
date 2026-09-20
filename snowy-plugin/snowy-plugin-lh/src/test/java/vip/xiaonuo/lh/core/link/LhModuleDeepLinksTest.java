package vip.xiaonuo.lh.core.link;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LhModuleDeepLinksTest {

    @Test
    void catalogAsset_and_search() {
        assertEquals("/catalog?asset=a1", LhModuleDeepLinks.catalogAsset("a1"));
        assertEquals("/catalog?q=dwd_order", LhModuleDeepLinks.catalogSearch("dwd_order"));
        assertEquals("/catalog", LhModuleDeepLinks.catalogAsset(null));
    }

    @Test
    void lineage_encodes_and_aliases() {
        String p = LhModuleDeepLinks.lineage("dwd.t", "om.fqn", "amt", "field");
        assertTrue(p.startsWith("/lineage?"));
        assertTrue(p.contains("focus="));
        assertTrue(p.contains("omFqn="));
        assertTrue(p.contains("field=amt"));
        assertTrue(p.contains("mode=field"));
        assertEquals("/lineage?focus=t1", LhModuleDeepLinks.lineageFocus("t1"));
    }

    @Test
    void quality_and_standard() {
        assertEquals("/quality?q=ads_gmv", LhModuleDeepLinks.quality("ads_gmv"));
        String s = LhModuleDeepLinks.standardMapping("order_status");
        assertTrue(s.contains("tab=mapping"));
        assertTrue(s.contains("q=order_status"));
    }
}
