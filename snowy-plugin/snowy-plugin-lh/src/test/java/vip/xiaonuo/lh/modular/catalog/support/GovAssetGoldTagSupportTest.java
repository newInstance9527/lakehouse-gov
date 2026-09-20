package vip.xiaonuo.lh.modular.catalog.support;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GovAssetGoldTagSupportTest {

    @Test
    void hasGoldTag_defaultTierGold() {
        assertTrue(GovAssetGoldTagSupport.hasGoldTag(List.of("Tier.Gold", "PII.Sensitive"), null));
        assertTrue(GovAssetGoldTagSupport.hasGoldTag(List.of("tier.gold"), List.of("Tier.Gold")));
        assertFalse(GovAssetGoldTagSupport.hasGoldTag(List.of("Tier.Silver"), null));
    }

    @Test
    void extractTagFqns_fromJsonArray() {
        JSONArray arr = new JSONArray();
        JSONObject t = new JSONObject();
        t.set("tagFQN", "Tier.Gold");
        arr.add(t);
        arr.add("Certification.Approved");
        List<String> fqns = GovAssetGoldTagSupport.extractTagFqns(arr);
        assertEquals(List.of("Tier.Gold", "Certification.Approved"), fqns);
    }

    @Test
    void reconcile_omWins() {
        Map<String, Object> drift = GovAssetGoldTagSupport.reconcile(false, List.of("Tier.Gold"), null);
        assertEquals(false, drift.get("aligned"));
        assertEquals(1, drift.get("desiredIsGold"));
        assertEquals("promote_portal", drift.get("action"));

        Map<String, Object> ok = GovAssetGoldTagSupport.reconcile(true, List.of("Tier.Gold"), null);
        assertEquals(true, ok.get("aligned"));
    }

    @Test
    void applyGoldFlag_addAndRemove() {
        List<String> with = GovAssetGoldTagSupport.applyGoldFlag(List.of("PII.None"), true, null);
        assertTrue(with.contains("Tier.Gold"));
        assertTrue(with.contains("PII.None"));

        List<String> without = GovAssetGoldTagSupport.applyGoldFlag(
                List.of("Tier.Gold", "PII.None"), false, null);
        assertFalse(without.contains("Tier.Gold"));
        assertTrue(without.contains("PII.None"));
    }
}
