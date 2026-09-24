package vip.xiaonuo.lh.core.ai;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容 Chat Completions（直连模型 baseUrl，不经 LiteLLM）。
 * <p>baseUrl 形如 {@code https://host/v1}；自动拼 {@code /chat/completions}。</p>
 */
@Component
public class LhOpenAiCompatClient {

    private static final Logger log = LoggerFactory.getLogger(LhOpenAiCompatClient.class);

    /**
     * @param baseUrl  登记的 API 根（含或不含 /v1）
     * @param apiKey   Bearer token；可空
     * @param model    上游模型名
     * @param messages [{role, content}, ...]
     * @return assistant 文本；失败返回 null
     */
    public String chat(String baseUrl, String apiKey, String model, List<Map<String, String>> messages) {
        Map<String, Object> probe = chatProbe(baseUrl, apiKey, model, messages);
        return Boolean.TRUE.equals(probe.get("ok")) ? (String) probe.get("content") : null;
    }

    public String chatSimple(String baseUrl, String apiKey, String model, String system, String user) {
        Map<String, Object> probe = chatSimpleProbe(baseUrl, apiKey, model, system, user);
        return Boolean.TRUE.equals(probe.get("ok")) ? (String) probe.get("content") : null;
    }

    /** 连通探测（直连）；ok 仅当 HTTP 2xx 且非空 completion */
    public Map<String, Object> chatSimpleProbe(String baseUrl, String apiKey, String model, String system, String user) {
        return chatProbe(baseUrl, apiKey, model, List.of(
                Map.of("role", "system", "content", StrUtil.blankToDefault(system, "You are a helpful assistant.")),
                Map.of("role", "user", "content", StrUtil.blankToDefault(user, ""))
        ));
    }

    public Map<String, Object> chatProbe(String baseUrl, String apiKey, String model, List<Map<String, String>> messages) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        if (StrUtil.isBlank(baseUrl) || StrUtil.isBlank(model) || messages == null || messages.isEmpty()) {
            out.put("error", "baseUrl / model / messages 不完整");
            return out;
        }
        try {
            String url = chatCompletionsUrl(baseUrl);
            JSONObject body = new JSONObject();
            body.set("model", model.trim());
            body.set("messages", messages);
            body.set("temperature", 0.2);
            HttpRequest req = HttpRequest.post(url)
                    .timeout(60_000)
                    .header("Content-Type", "application/json")
                    .body(body.toString());
            if (StrUtil.isNotBlank(apiKey)) {
                req.header("Authorization", "Bearer " + apiKey.trim());
            }
            try (HttpResponse resp = req.execute()) {
                int code = resp.getStatus();
                String raw = resp.body();
                out.put("httpStatus", code);
                if (code < 200 || code >= 300) {
                    String err = parseErrorBody(raw);
                    out.put("error", "HTTP " + code + (StrUtil.isBlank(err) ? "" : (" · " + err)));
                    log.warn("OpenAI-compat chat HTTP {} body={}", code, StrUtil.maxLength(raw, 240));
                    return out;
                }
                if (StrUtil.isBlank(raw)) {
                    out.put("error", "上游返回空响应");
                    return out;
                }
                JSONObject root = JSONUtil.parseObj(raw);
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
                String content = msg == null ? null : msg.getStr("content");
                if (StrUtil.isBlank(content)) {
                    out.put("error", "上游返回空 completion");
                    return out;
                }
                out.put("ok", true);
                out.put("content", content);
                return out;
            }
        } catch (Exception e) {
            log.warn("OpenAI-compat chat failed: {}", e.getMessage());
            out.put("error", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
            return out;
        }
    }

    private static String parseErrorBody(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        try {
            if (JSONUtil.isTypeJSONObject(raw)) {
                return LhLiteLlmClient.extractUpstreamError(JSONUtil.parseObj(raw));
            }
        } catch (Exception ignored) {
            // fall through
        }
        return StrUtil.maxLength(raw, 240);
    }

    /** 规范化为 .../v1/chat/completions */
    static String chatCompletionsUrl(String baseUrl) {
        String u = StrUtil.removeSuffix(baseUrl.trim(), "/");
        if (u.endsWith("/chat/completions")) {
            return u;
        }
        if (u.endsWith("/v1")) {
            return u + "/chat/completions";
        }
        if (u.contains("/v1/")) {
            return u + (u.endsWith("/") ? "chat/completions" : "/chat/completions");
        }
        return u + "/v1/chat/completions";
    }
}
