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
}
