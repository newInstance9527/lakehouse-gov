package vip.xiaonuo.lh.core.ai;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completions + tools 响应解析（LiteLLM / 直连共用）。
 */
public final class LhChatTools {

    private LhChatTools() {
    }

    /**
     * 从 choices[0].message 解析 content + tool_calls。
     * ok=true 当存在非空 content 或至少一个 tool_call。
     */
    public static Map<String, Object> parseAssistantMessage(JSONObject root) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        if (root == null) {
            out.put("error", "空响应");
            return out;
        }
        String upstreamErr = LhLiteLlmClient.extractUpstreamError(root);
        if (StrUtil.isNotBlank(upstreamErr)) {
            out.put("error", upstreamErr);
            return out;
        }
        JSONArray choices = root.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            out.put("error", "上游响应无 choices");
            return out;
        }
        JSONObject msg = choices.getJSONObject(0).getJSONObject("message");
        if (msg == null) {
            out.put("error", "上游响应无 message");
            return out;
        }
        String content = msg.getStr("content");
        List<Map<String, Object>> toolCalls = parseToolCalls(msg.getJSONArray("tool_calls"));
        out.put("content", StrUtil.nullToEmpty(content));
        out.put("tool_calls", toolCalls);
        if (StrUtil.isNotBlank(content) || !toolCalls.isEmpty()) {
            out.put("ok", true);
        } else {
            out.put("error", "上游返回空 completion 且无 tool_calls");
        }
        Object usage = root.get("usage");
        if (usage != null) {
            out.put("usage", usage);
        }
        return out;
    }

    public static List<Map<String, Object>> parseToolCalls(JSONArray arr) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (arr == null || arr.isEmpty()) {
            return out;
        }
        for (int i = 0; i < arr.size(); i++) {
            JSONObject tc = arr.getJSONObject(i);
            if (tc == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", StrUtil.blankToDefault(tc.getStr("id"), "call_" + i));
            row.put("type", StrUtil.blankToDefault(tc.getStr("type"), "function"));
            JSONObject fn = tc.getJSONObject("function");
            String name = fn == null ? null : fn.getStr("name");
            String args = fn == null ? "{}" : StrUtil.blankToDefault(fn.getStr("arguments"), "{}");
            row.put("name", StrUtil.nullToEmpty(name));
            row.put("arguments", args);
            if (StrUtil.isNotBlank(name)) {
                out.add(row);
            }
        }
        return out;
    }

    /** 构建 assistant 消息（含 tool_calls）供下一轮 messages */
    public static Map<String, Object> assistantToolCallMessage(String content, List<Map<String, Object>> toolCalls) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "assistant");
        msg.put("content", content == null ? "" : content);
        if (toolCalls != null && !toolCalls.isEmpty()) {
            List<Map<String, Object>> encoded = new ArrayList<>();
            for (Map<String, Object> tc : toolCalls) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("id", tc.get("id"));
                one.put("type", "function");
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", tc.get("name"));
                fn.put("arguments", StrUtil.blankToDefault(String.valueOf(tc.get("arguments")), "{}"));
                one.put("function", fn);
                encoded.add(one);
            }
            msg.put("tool_calls", encoded);
        }
        return msg;
    }

    public static Map<String, Object> toolResultMessage(String toolCallId, String content) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "tool");
        msg.put("tool_call_id", toolCallId);
        msg.put("content", StrUtil.blankToDefault(content, ""));
        return msg;
    }

    public static Map<String, Object> parseArgumentsObject(String argumentsJson) {
        if (StrUtil.isBlank(argumentsJson)) {
            return Map.of();
        }
        try {
            if (JSONUtil.isTypeJSONObject(argumentsJson)) {
                JSONObject obj = JSONUtil.parseObj(argumentsJson);
                Map<String, Object> out = new LinkedHashMap<>();
                for (String key : obj.keySet()) {
                    out.put(key, obj.get(key));
                }
                return out;
            }
        } catch (Exception ignored) {
            // fall through
        }
        return Map.of("_raw", argumentsJson);
    }
}
