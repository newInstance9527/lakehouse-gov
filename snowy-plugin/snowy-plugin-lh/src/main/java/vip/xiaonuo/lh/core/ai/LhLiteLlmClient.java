package vip.xiaonuo.lh.core.ai;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LiteLLM OpenAI 兼容客户端（P0）
 * <p>未启用或 url 为空时返回 null，调用方走启发式。</p>
 */
@Component
public class LhLiteLlmClient {

    @Resource
    private LhProperties lhProperties;

    /** 是否可用 */
    public boolean available() {
        LhProperties.Ai ai = lhProperties.getAi();
        return ai != null && ai.isEnabled() && StrUtil.isNotBlank(ai.getLitellmUrl());
    }

    /**
     * Chat Completions
     *
     * @param model    模型别名或上游名
     * @param messages [{role, content}, ...]
     * @return assistant 文本；不可用或失败返回 null
     */
    public String chat(String model, List<Map<String, String>> messages) {
        if (!available()) {
            return null;
        }
        LhProperties.Ai ai = lhProperties.getAi();
        String useModel = StrUtil.blankToDefault(model, ai.getDefaultChatModel());
        if (StrUtil.isBlank(useModel) || messages == null || messages.isEmpty()) {
            return null;
        }
        try {
            JSONObject body = new JSONObject();
            body.set("model", useModel);
            body.set("messages", messages);
            body.set("temperature", 0.2);
            String resp = postJson("/v1/chat/completions", body.toString());
            if (StrUtil.isBlank(resp)) {
                return null;
            }
            JSONObject root = JSONUtil.parseObj(resp);
            JSONArray choices = root.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                return null;
            }
            JSONObject msg = choices.getJSONObject(0).getJSONObject("message");
            return msg == null ? null : msg.getStr("content");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Embeddings（可选；失败返回 null）
     *
     * @param model  embed 模型
     * @param inputs 文本列表
     * @return 向量列表；不可用返回 null
     */
    public List<float[]> embed(String model, List<String> inputs) {
        if (!available() || inputs == null || inputs.isEmpty()) {
            return null;
        }
        LhProperties.Ai ai = lhProperties.getAi();
        String useModel = StrUtil.blankToDefault(model, ai.getDefaultEmbedModel());
        if (StrUtil.isBlank(useModel)) {
            return null;
        }
        try {
            JSONObject body = new JSONObject();
            body.set("model", useModel);
            body.set("input", inputs);
            String resp = postJson("/v1/embeddings", body.toString());
            if (StrUtil.isBlank(resp)) {
                return null;
            }
            JSONArray data = JSONUtil.parseObj(resp).getJSONArray("data");
            if (data == null || data.isEmpty()) {
                return null;
            }
            List<float[]> out = new ArrayList<>(data.size());
            for (int i = 0; i < data.size(); i++) {
                JSONArray emb = data.getJSONObject(i).getJSONArray("embedding");
                if (emb == null) {
                    continue;
                }
                float[] vec = new float[emb.size()];
                for (int j = 0; j < emb.size(); j++) {
                    vec[j] = emb.getFloat(j);
                }
                out.add(vec);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** 便捷：单轮 user 消息 */
    public String chatSimple(String model, String system, String user) {
        List<Map<String, String>> msgs = new ArrayList<>();
        if (StrUtil.isNotBlank(system)) {
            Map<String, String> s = new LinkedHashMap<>();
            s.put("role", "system");
            s.put("content", system);
            msgs.add(s);
        }
        Map<String, String> u = new LinkedHashMap<>();
        u.put("role", "user");
        u.put("content", StrUtil.nullToEmpty(user));
        msgs.add(u);
        return chat(model, msgs);
    }

    /**
     * 网关存活探测（D1 巡检）。
     * 优先 {@code GET /health/liveliness}，失败再试 {@code /health} / {@code /v1/models}。
     *
     * @return ok=true 时表示可达；不可用时 ok=false 且带 message
     */
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!available()) {
            out.put("ok", false);
            out.put("available", false);
            out.put("message", "LiteLLM 未启用或 litellm-url 为空");
            return out;
        }
        LhProperties.Ai ai = lhProperties.getAi();
        String base = StrUtil.removeSuffix(ai.getLitellmUrl().trim(), "/");
        String[] paths = {"/health/liveliness", "/health", "/v1/models"};
        Exception last = null;
        for (String path : paths) {
            try {
                HttpRequest req = HttpRequest.get(base + path).timeout(8_000);
                if (StrUtil.isNotBlank(ai.getLitellmMasterKey())) {
                    req.header("Authorization", "Bearer " + ai.getLitellmMasterKey());
                }
                int code = req.execute().getStatus();
                if (code >= 200 && code < 300) {
                    out.put("ok", true);
                    out.put("available", true);
                    out.put("path", path);
                    out.put("httpStatus", code);
                    out.put("baseUrl", base);
                    out.put("message", "LiteLLM 可达");
                    return out;
                }
                last = new IllegalStateException("HTTP " + code + " on " + path);
            } catch (Exception e) {
                last = e;
            }
        }
        out.put("ok", false);
        out.put("available", true);
        out.put("baseUrl", base);
        out.put("message", last == null ? "LiteLLM 健康检查失败" : last.getMessage());
        return out;
    }

    private String postJson(String path, String jsonBody) {
        LhProperties.Ai ai = lhProperties.getAi();
        String base = StrUtil.removeSuffix(ai.getLitellmUrl().trim(), "/");
        HttpRequest req = HttpRequest.post(base + path)
                .timeout(30_000)
                .header("Content-Type", "application/json");
        if (StrUtil.isNotBlank(ai.getLitellmMasterKey())) {
            req.header("Authorization", "Bearer " + ai.getLitellmMasterKey());
        }
        return req.body(jsonBody).execute().body();
    }
}
