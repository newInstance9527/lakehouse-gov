package vip.xiaonuo.lh.modular.ai.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovAiAgentOrchestratorMemoryTest {

    @Test
    void trimHistory_keepsTail() {
        List<Map<String, Object>> hist = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            hist.add(Map.of("role", i % 2 == 0 ? "user" : "assistant", "content", "m" + i));
        }
        List<Map<String, Object>> trimmed = GovAiAgentOrchestrator.trimHistory(hist, 4);
        assertEquals(4, trimmed.size());
        assertEquals("m8", trimmed.get(0).get("content"));
        assertEquals("m11", trimmed.get(3).get("content"));
    }

    @Test
    void buildMessages_injectsSummaryAndHistory() {
        List<Map<String, Object>> hist = List.of(
                Map.of("role", "user", "content", "表 A 怎么查"),
                Map.of("role", "assistant", "content", "需要申请读权"));
        List<Map<String, Object>> msgs = GovAiAgentOrchestrator.buildMessages(
                "ws_demo", "我申请了吗", hist, "用户关心表 A 读权");
        assertTrue(msgs.size() >= 4);
        assertEquals("system", msgs.get(0).get("role"));
        assertTrue(String.valueOf(msgs.get(1).get("content")).contains("会话工作记忆摘要"));
        assertEquals("user", msgs.get(msgs.size() - 1).get("role"));
        assertEquals("我申请了吗", msgs.get(msgs.size() - 1).get("content"));
    }
}
