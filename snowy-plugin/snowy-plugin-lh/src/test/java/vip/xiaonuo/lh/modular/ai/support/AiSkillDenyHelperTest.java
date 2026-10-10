package vip.xiaonuo.lh.modular.ai.support;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiSkillDenyHelperTest {

    @Test
    void denyReadHasProtocolFields() {
        Map<String, Object> denied = AiSkillDenyHelper.denyRead("a1", "ods_x", "无表读权限");
        assertEquals(AiSkillDenyHelper.ERROR_FORBIDDEN, denied.get("error"));
        assertTrue(String.valueOf(denied.get("message")).contains("无表读权限"));
        assertTrue(denied.get("howTo") instanceof List<?>);
        assertTrue(String.valueOf(denied.get("applyHref")).contains("/apply?type=perm"));
        assertTrue(String.valueOf(denied.get("applyHref")).contains("assetId=a1"));
        assertTrue(String.valueOf(denied.get("portalHref")).contains("/catalog"));
        assertEquals(denied.get("applyHref"), denied.get("href"));
    }

    @Test
    void denyDsPointsToManageApply() {
        Map<String, Object> denied = AiSkillDenyHelper.denyDs("ds9", null);
        assertEquals(AiSkillDenyHelper.ERROR_FORBIDDEN, denied.get("error"));
        assertEquals("ds9", denied.get("dsId"));
        assertTrue(String.valueOf(denied.get("applyHref")).contains("resourceType=datasource"));
        assertTrue(String.valueOf(denied.get("applyHref")).contains("resourceId=ds9"));
    }

    @Test
    void extractApplyHrefFromJson() {
        String json = "{\"error\":\"forbidden\",\"applyHref\":\"/apply?type=perm&assetId=x\"}";
        assertEquals("/apply?type=perm&assetId=x", AiSkillDenyHelper.extractApplyHref(json));
    }

    @Test
    void deeplinkActionsIncludeApply() {
        List<Map<String, Object>> actions = AiSkillDenyHelper.deeplinkActions("/apply?type=perm", "/catalog");
        assertEquals(2, actions.size());
        assertEquals("deeplink", actions.get(0).get("type"));
        assertEquals("/apply?type=perm", actions.get(0).get("href"));
    }
}
