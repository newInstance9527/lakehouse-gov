package vip.xiaonuo.lh.modular.ai.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentRouterTest {

    @Test
    void routeApiScriptScenes() {
        assertEquals("api_script", IntentRouter.route("任意", "api_script"));
        assertEquals("api_script", IntentRouter.route("任意", "build_api"));
        assertEquals("api_script", IntentRouter.route("任意", "api-build"));
    }

    @Test
    void modelSceneIncludesApiScript() {
        assertEquals("sql", IntentRouter.modelScene("api_script"));
    }

    @Test
    void wantsGroovyFromScriptTypeOrText() {
        assertTrue(IntentRouter.wantsGroovy("GROOVY", "写查询"));
        assertTrue(IntentRouter.wantsGroovy("SQL", "请用 groovy 脚本"));
        assertFalse(IntentRouter.wantsGroovy("SQL", "生成只读 SQL"));
        assertFalse(IntentRouter.wantsGroovy(null, "生成查询"));
    }

    @Test
    void listAssetsBeatsNl2sqlQueryKeyword() {
        assertEquals("list_assets", IntentRouter.route("我可以查询哪些资源", null));
        assertEquals("list_assets", IntentRouter.route("当前空间可查资源有哪些", null));
        assertEquals("list_assets", IntentRouter.route("我有哪些表", null));
        assertEquals("list_assets", IntentRouter.route("任意", "list_assets"));
        assertEquals("nl2sql", IntentRouter.route("帮我写查询近7天订单", null));
    }
}
