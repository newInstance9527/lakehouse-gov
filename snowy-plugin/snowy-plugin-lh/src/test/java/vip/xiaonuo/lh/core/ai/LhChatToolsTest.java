package vip.xiaonuo.lh.core.ai;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LhChatToolsTest {

    @Test
    void parseToolCallsFromMessage() {
        cn.hutool.json.JSONObject root = cn.hutool.json.JSONUtil.parseObj("""
                {
                  "choices": [{
                    "message": {
                      "content": "",
                      "tool_calls": [{
                        "id": "call_1",
                        "type": "function",
                        "function": { "name": "catalog_stats", "arguments": "{\\"ws\\":\\"default\\"}" }
                      }]
                    }
                  }]
                }
                """);
        Map<String, Object> parsed = LhChatTools.parseAssistantMessage(root);
        assertTrue(Boolean.TRUE.equals(parsed.get("ok")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> calls = (List<Map<String, Object>>) parsed.get("tool_calls");
        assertEquals(1, calls.size());
        assertEquals("catalog_stats", calls.get(0).get("name"));
        Map<String, Object> args = LhChatTools.parseArgumentsObject(String.valueOf(calls.get(0).get("arguments")));
        assertEquals("default", args.get("ws"));
    }

    @Test
    void emptyWithoutToolsIsNotOk() {
        cn.hutool.json.JSONObject root = cn.hutool.json.JSONUtil.parseObj("""
                { "choices": [{ "message": { "content": "" } }] }
                """);
        Map<String, Object> parsed = LhChatTools.parseAssistantMessage(root);
        assertFalse(Boolean.TRUE.equals(parsed.get("ok")));
    }

    @Test
    void assistantAndToolMessagesRoundTripShape() {
        Map<String, Object> asst = LhChatTools.assistantToolCallMessage("", List.of(
                Map.of("id", "c1", "name", "kb_search", "arguments", "{\"query\":\"x\"}")));
        assertEquals("assistant", asst.get("role"));
        assertTrue(asst.containsKey("tool_calls"));
        Map<String, Object> tool = LhChatTools.toolResultMessage("c1", "{\"ok\":true}");
        assertEquals("tool", tool.get("role"));
        assertEquals("c1", tool.get("tool_call_id"));
    }
}
