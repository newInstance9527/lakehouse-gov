package vip.xiaonuo.lh.modular.ai.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentRouterAgentBypassTest {

    @Test
    void generationScenesBypassAgent() {
        assertTrue(IntentRouter.isGenerationBypassScene("nl2sql"));
        assertTrue(IntentRouter.isGenerationBypassScene("sql-opt"));
        assertTrue(IntentRouter.isGenerationBypassScene("gen_script"));
        assertTrue(IntentRouter.isGenerationBypassScene("api_script"));
        assertTrue(IntentRouter.isGenerationBypassScene("build_api"));
    }

    @Test
    void exploreScenesStayOnAgent() {
        assertFalse(IntentRouter.isGenerationBypassScene(null));
        assertFalse(IntentRouter.isGenerationBypassScene(""));
        assertFalse(IntentRouter.isGenerationBypassScene("ask_data"));
        assertFalse(IntentRouter.isGenerationBypassScene("list_assets"));
        assertFalse(IntentRouter.isGenerationBypassScene("docqa"));
        assertFalse(IntentRouter.isGenerationBypassScene("agent"));
    }

    @Test
    void agentSceneRoutesAndModelScene() {
        assertEquals("agent", IntentRouter.route("任意", "agent"));
        assertEquals("agent", IntentRouter.route("任意", "explore"));
        assertEquals("manual", IntentRouter.modelScene("agent"));
    }
}
