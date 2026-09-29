package vip.xiaonuo.lh.core.ai;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * LiteLLM OpenAI 兼容客户端（P0）
 * <p>未启用或 url 为空时返回 null，调用方走启发式。</p>
 * <p>D3：门户 {@code gov_ai_model} 别名 {@code lh/{id}} 与启停同步；网关不可达时软降级，不阻断门户 CRUD。</p>
 */
@Component
public class LhLiteLlmClient {

    private static final Logger log = LoggerFactory.getLogger(LhLiteLlmClient.class);

    /** 门户登记 → LiteLLM model_name 前缀，避免与上游原名冲突 */
    public static final String ALIAS_PREFIX = "lh/";

    @Resource
    private LhProperties lhProperties;

    /** 是否可用 */
    public boolean available() {
        LhProperties.Ai ai = lhProperties.getAi();
        return ai != null && ai.isEnabled() && StrUtil.isNotBlank(ai.getLitellmUrl());
    }

    /** 门户模型 id → LiteLLM 别名 */
    public static String aliasOf(String modelId) {
        if (StrUtil.isBlank(modelId)) {
            return "";
        }
        String id = modelId.trim();
        return id.startsWith(ALIAS_PREFIX) ? id : ALIAS_PREFIX + id;
    }

    /**
     * Chat Completions
     *
     * @param model    模型别名或上游名
     * @param messages [{role, content}, ...]
     * @return assistant 文本；不可用或失败返回 null
     */
    public String chat(String model, List<Map<String, String>> messages) {
        Map<String, Object> probe = chatProbe(model, messages);
        return Boolean.TRUE.equals(probe.get("ok")) ? (String) probe.get("content") : null;
    }

    /**
     * Embeddings（可选；失败返回 null）
     *
     * @param model  embed 模型
     * @param inputs 文本列表
     * @return 向量列表；不可用返回 null
     */
    public List<float[]> embed(String model, List<String> inputs) {
        Map<String, Object> probe = embedProbe(model, inputs);
        @SuppressWarnings("unchecked")
        List<float[]> vecs = (List<float[]>) probe.get("vectors");
        return Boolean.TRUE.equals(probe.get("ok")) ? vecs : null;
    }

    /** 便捷：单轮 user 消息 */
    public String chatSimple(String model, String system, String user) {
        Map<String, Object> probe = chatSimpleProbe(model, system, user);
        return Boolean.TRUE.equals(probe.get("ok")) ? (String) probe.get("content") : null;
    }

    /**
     * 连通探测：Chat Completions。
     * <p>ok=true 仅当 HTTP 2xx 且解析到非空 assistant content；上游 error JSON 写入 error 字段。</p>
     */
    public Map<String, Object> chatSimpleProbe(String model, String system, String user) {
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
        return chatProbe(model, msgs);
    }

    public Map<String, Object> chatProbe(String model, List<Map<String, String>> messages) {
        List<Map<String, Object>> msgs = new ArrayList<>();
        if (messages != null) {
            for (Map<String, String> m : messages) {
                if (m == null) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("role", m.get("role"));
                row.put("content", m.get("content"));
                msgs.add(row);
            }
        }
        return chatWithTools(model, msgs, null);
    }

    /**
     * Chat Completions（支持 tools）。ok=true 时含 content、tool_calls。
     */
    public Map<String, Object> chatWithTools(String model, List<Map<String, Object>> messages,
                                             List<Map<String, Object>> tools) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        if (!available()) {
            out.put("error", "LiteLLM 未启用或 litellm-url 为空");
            return out;
        }
        LhProperties.Ai ai = lhProperties.getAi();
        String useModel = StrUtil.blankToDefault(model, ai.getDefaultChatModel());
        if (StrUtil.isBlank(useModel) || messages == null || messages.isEmpty()) {
            out.put("error", "模型或消息为空");
            return out;
        }
        try {
            JSONObject body = new JSONObject();
            body.set("model", useModel);
            body.set("messages", messages);
            body.set("temperature", 0.2);
            if (tools != null && !tools.isEmpty()) {
                body.set("tools", tools);
                body.set("tool_choice", "auto");
            }
            Map<String, Object> http = postJsonResult("/v1/chat/completions", body.toString());
            out.put("httpStatus", http.get("httpStatus"));
            out.put("model", useModel);
            if (!Boolean.TRUE.equals(http.get("ok"))) {
                out.put("error", http.get("error"));
                return out;
            }
            String resp = (String) http.get("body");
            if (StrUtil.isBlank(resp)) {
                out.put("error", "上游返回空响应");
                return out;
            }
            JSONObject root = JSONUtil.parseObj(resp);
            Map<String, Object> parsed = LhChatTools.parseAssistantMessage(root);
            out.putAll(parsed);
            return out;
        } catch (Exception e) {
            out.put("error", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
            return out;
        }
    }

    /**
     * 流式 Chat Completions（无 tools）。onDelta 为 content 增量。
     */
    public Map<String, Object> chatStream(String model, List<Map<String, Object>> messages,
                                          Consumer<String> onDelta) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        if (!available()) {
            out.put("error", "LiteLLM 未启用或 litellm-url 为空");
            return out;
        }
        LhProperties.Ai ai = lhProperties.getAi();
        String useModel = StrUtil.blankToDefault(model, ai.getDefaultChatModel());
        if (StrUtil.isBlank(useModel) || messages == null || messages.isEmpty()) {
            out.put("error", "模型或消息为空");
            return out;
        }
        try {
            String base = StrUtil.removeSuffix(ai.getLitellmUrl().trim(), "/");
            String url = base + "/v1/chat/completions";
            JSONObject body = new JSONObject();
            body.set("model", useModel);
            body.set("messages", messages);
            body.set("temperature", 0.2);
            body.set("stream", true);
            String masterKey = ai.getLitellmMasterKey();
            Map<String, Object> streamed = LhOpenAiSseStream.stream(url, masterKey, body.toString(), onDelta);
            streamed.put("model", useModel);
            return streamed;
        } catch (Exception e) {
            out.put("error", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
            return out;
        }
    }

    public Map<String, Object> chatStreamSimple(String model, String system, String user,
                                                Consumer<String> onDelta) {
        List<Map<String, Object>> msgs = new ArrayList<>();
        if (StrUtil.isNotBlank(system)) {
            msgs.add(Map.of("role", "system", "content", system));
        }
        msgs.add(Map.of("role", "user", "content", StrUtil.nullToEmpty(user)));
        return chatStream(model, msgs, onDelta);
    }

    /**
     * 连通探测：Embeddings。
     * <p>ok=true 仅当 HTTP 2xx 且至少一条非空向量。</p>
     */
    public Map<String, Object> embedProbe(String model, List<String> inputs) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        if (!available()) {
            out.put("error", "LiteLLM 未启用或 litellm-url 为空");
            return out;
        }
        if (inputs == null || inputs.isEmpty()) {
            out.put("error", "embedding 输入为空");
            return out;
        }
        LhProperties.Ai ai = lhProperties.getAi();
        String useModel = StrUtil.blankToDefault(model, ai.getDefaultEmbedModel());
        if (StrUtil.isBlank(useModel)) {
            out.put("error", "embedding 模型未配置");
            return out;
        }
        try {
            JSONObject body = new JSONObject();
            body.set("model", useModel);
            body.set("input", inputs);
            Map<String, Object> http = postJsonResult("/v1/embeddings", body.toString());
            out.put("httpStatus", http.get("httpStatus"));
            out.put("model", useModel);
            if (!Boolean.TRUE.equals(http.get("ok"))) {
                out.put("error", http.get("error"));
                return out;
            }
            String resp = (String) http.get("body");
            if (StrUtil.isBlank(resp)) {
                out.put("error", "上游返回空响应");
                return out;
            }
            JSONObject root = JSONUtil.parseObj(resp);
            String upstreamErr = extractUpstreamError(root);
            if (StrUtil.isNotBlank(upstreamErr)) {
                out.put("error", upstreamErr);
                return out;
            }
            JSONArray data = root.getJSONArray("data");
            if (data == null || data.isEmpty()) {
                out.put("error", "上游响应无 embedding data");
                return out;
            }
            List<float[]> vectors = new ArrayList<>(data.size());
            for (int i = 0; i < data.size(); i++) {
                JSONArray emb = data.getJSONObject(i).getJSONArray("embedding");
                if (emb == null || emb.isEmpty()) {
                    continue;
                }
                float[] vec = new float[emb.size()];
                for (int j = 0; j < emb.size(); j++) {
                    vec[j] = emb.getFloat(j);
                }
                vectors.add(vec);
            }
            if (vectors.isEmpty()) {
                out.put("error", "上游返回空向量");
                return out;
            }
            out.put("ok", true);
            out.put("vectors", vectors);
            return out;
        } catch (Exception e) {
            out.put("error", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
            return out;
        }
    }

    /**
     * 从 OpenAI / LiteLLM 错误 JSON 提取可读文案。
     * 兼容 {@code error.message}、{@code error} 字符串、{@code detail}、{@code message}。
     */
    static String extractUpstreamError(JSONObject root) {
        if (root == null) {
            return null;
        }
        Object err = root.get("error");
        if (err instanceof JSONObject errObj) {
            String msg = firstStr(errObj, "message", "msg", "detail", "type");
            if (StrUtil.isNotBlank(msg)) {
                String code = firstStr(errObj, "code", "type");
                return StrUtil.isNotBlank(code) && !code.equals(msg) ? (msg + " (" + code + ")") : msg;
            }
            return StrUtil.maxLength(errObj.toString(), 240);
        }
        if (err instanceof CharSequence && StrUtil.isNotBlank(err.toString())) {
            return err.toString().trim();
        }
        String detail = firstStr(root, "detail", "message", "msg");
        // 有 choices/data 的成功体里也可能带 message 元数据，勿误判
        if (StrUtil.isNotBlank(detail) && root.getJSONArray("choices") == null && root.getJSONArray("data") == null) {
            return detail;
        }
        return null;
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

    /**
     * 同步探针（D3）：开关 / URL / 健康 / 是否可做 model management。
     * 不可达时调用方应跳过同步，门户登记仍为 SoT。
     */
    public Map<String, Object> probeSync() {
        Map<String, Object> out = new LinkedHashMap<>();
        LhProperties.Ai ai = lhProperties.getAi();
        boolean enabled = ai != null && ai.isEnabled() && StrUtil.isNotBlank(ai.getLitellmUrl());
        out.put("enabled", enabled);
        out.put("baseUrl", enabled ? StrUtil.removeSuffix(ai.getLitellmUrl().trim(), "/") : "");
        out.put("aliasPrefix", ALIAS_PREFIX);
        if (!enabled) {
            out.put("reachable", false);
            out.put("syncCapable", false);
            out.put("mode", "skipped");
            out.put("message", "LiteLLM 未启用或 litellm-url 为空；门户登记不阻断");
            return out;
        }
        Map<String, Object> h = health();
        boolean reachable = Boolean.TRUE.equals(h.get("ok"));
        out.put("reachable", reachable);
        out.put("health", h);
        if (!reachable) {
            out.put("syncCapable", false);
            out.put("mode", "degraded");
            out.put("message", "LiteLLM 不可达；启停仅写门户库");
            return out;
        }
        // 探测 /model/info：200 即管理面可用；无 DB 时 /model/new 仍可能失败，由 upsert 软降级
        try {
            HttpResponse resp = request("GET", "/model/info", null);
            int code = resp.getStatus();
            out.put("modelInfoHttpStatus", code);
            boolean syncCapable = code >= 200 && code < 300;
            out.put("syncCapable", syncCapable);
            out.put("mode", syncCapable ? "live" : "degraded");
            out.put("message", syncCapable
                    ? "LiteLLM 可达；别名/启停可尝试同步（需网关 store_model_in_db 或兼容管理 API）"
                    : "LiteLLM 可达但 /model/info 失败；同步将软跳过");
        } catch (Exception e) {
            out.put("syncCapable", false);
            out.put("mode", "degraded");
            out.put("message", "探测 /model/info 异常：" + e.getMessage());
        }
        return out;
    }

    /**
     * 将门户模型登记 upsert 到 LiteLLM（别名 {@code lh/{id}}）。
     * <p>厂商 Key <b>不</b>从 Vault 注入；api_key 仅写 {@code os.environ/...} 占位，与 ops/litellm 一致。</p>
     * <p>停用：优先 {@code /model/block}；失败则 {@code /model/delete}。</p>
     *
     * @return ok / skipped / alias / message；永不抛阻断门户事务的业务异常
     */
    public Map<String, Object> upsertAlias(String modelId, String vendor, String upstreamModel,
                                           String apiBase, String kind, boolean enabled) {
        return upsertAlias(modelId, vendor, upstreamModel, apiBase, kind, enabled, null);
    }

    public Map<String, Object> upsertAlias(String modelId, String vendor, String upstreamModel,
                                           String apiBase, String kind, boolean enabled, String apiKey) {
        Map<String, Object> out = new LinkedHashMap<>();
        String alias = aliasOf(modelId);
        out.put("alias", alias);
        out.put("enabled", enabled);
        if (!available()) {
            out.put("ok", false);
            out.put("skipped", true);
            out.put("message", "LiteLLM 未启用；已跳过别名同步");
            return out;
        }
        if (StrUtil.isBlank(upstreamModel)) {
            out.put("ok", false);
            out.put("skipped", true);
            out.put("message", "upstream model 为空；跳过同步");
            return out;
        }
        try {
            String existingId = findModelIdByAlias(alias);
            if (!enabled) {
                return disableAlias(alias, existingId);
            }
            JSONObject body = buildDeploymentBody(alias, vendor, upstreamModel, apiBase, kind, apiKey);
            if (StrUtil.isNotBlank(existingId)) {
                body.set("id", existingId);
                HttpResponse resp = request("POST", "/model/update", body.toString());
                if (isSuccess(resp)) {
                    out.put("ok", true);
                    out.put("skipped", false);
                    out.put("action", "update");
                    out.put("litellmModelId", existingId);
                    out.put("message", "已更新 LiteLLM 别名");
                    unblockQuietly(existingId);
                    return out;
                }
                // update 失败：尝试 new（部分版本无 update）
            }
            HttpResponse created = request("POST", "/model/new", body.toString());
            if (isSuccess(created)) {
                out.put("ok", true);
                out.put("skipped", false);
                out.put("action", "create");
                out.put("litellmModelId", extractModelId(created.body()));
                out.put("message", "已注册 LiteLLM 别名");
                return out;
            }
            out.put("ok", false);
            out.put("skipped", false);
            out.put("action", "upsert");
            out.put("httpStatus", created.getStatus());
            out.put("message", softFailMessage("注册别名失败", created));
            log.warn("LiteLLM upsertAlias failed alias={} status={} body={}",
                    alias, created.getStatus(), StrUtil.maxLength(created.body(), 240));
            return out;
        } catch (Exception e) {
            out.put("ok", false);
            out.put("skipped", false);
            out.put("message", "同步异常（已降级）：" + e.getMessage());
            log.warn("LiteLLM upsertAlias exception alias={}: {}", alias, e.getMessage());
            return out;
        }
    }

    /**
     * 启停联动：启用 → upsert（调用方应带齐元数据）或 unblock；停用 → block / delete。
     */
    public Map<String, Object> setAliasEnabled(String modelId, boolean enabled,
                                               String vendor, String upstreamModel, String apiBase, String kind) {
        if (enabled) {
            return upsertAlias(modelId, vendor, upstreamModel, apiBase, kind, true);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        String alias = aliasOf(modelId);
        out.put("alias", alias);
        out.put("enabled", false);
        if (!available()) {
            out.put("ok", false);
            out.put("skipped", true);
            out.put("message", "LiteLLM 未启用；已跳过停用同步");
            return out;
        }
        try {
            String existingId = findModelIdByAlias(alias);
            return disableAlias(alias, existingId);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("skipped", false);
            out.put("message", "停用同步异常（已降级）：" + e.getMessage());
            log.warn("LiteLLM setAliasEnabled exception alias={}: {}", alias, e.getMessage());
            return out;
        }
    }

    /**
     * 将场景路由主/备下发为 LiteLLM fallbacks（best-effort）。
     * 条目形如 {@code [{ "lh/primary": ["lh/fallback"] }, ...]}。
     */
    public Map<String, Object> syncFallbacks(List<Map<String, List<String>>> fallbacks) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!available()) {
            out.put("ok", false);
            out.put("skipped", true);
            out.put("message", "LiteLLM 未启用；已跳过 fallback 同步");
            return out;
        }
        if (fallbacks == null || fallbacks.isEmpty()) {
            out.put("ok", true);
            out.put("skipped", true);
            out.put("message", "无 fallback 可同步");
            return out;
        }
        try {
            JSONObject body = new JSONObject();
            JSONObject router = new JSONObject();
            router.set("fallbacks", fallbacks);
            body.set("router_settings", router);
            HttpResponse resp = request("POST", "/config/update", body.toString());
            if (isSuccess(resp)) {
                out.put("ok", true);
                out.put("skipped", false);
                out.put("count", fallbacks.size());
                out.put("message", "已同步 router fallbacks");
                return out;
            }
            out.put("ok", false);
            out.put("skipped", false);
            out.put("httpStatus", resp.getStatus());
            out.put("message", softFailMessage("fallback 同步失败", resp));
            return out;
        } catch (Exception e) {
            out.put("ok", false);
            out.put("skipped", false);
            out.put("message", "fallback 同步异常（已降级）：" + e.getMessage());
            return out;
        }
    }

    private Map<String, Object> disableAlias(String alias, String existingId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("alias", alias);
        out.put("enabled", false);
        if (StrUtil.isBlank(existingId)) {
            out.put("ok", true);
            out.put("skipped", true);
            out.put("action", "noop");
            out.put("message", "网关无此别名；停用视为已同步");
            return out;
        }
        // 优先 block（保留定义）；失败再 delete
        JSONObject blockBody = new JSONObject();
        blockBody.set("model_id", existingId);
        HttpResponse blocked = request("POST", "/model/block", blockBody.toString());
        if (isSuccess(blocked)) {
            out.put("ok", true);
            out.put("skipped", false);
            out.put("action", "block");
            out.put("litellmModelId", existingId);
            out.put("message", "已 block LiteLLM 别名");
            return out;
        }
        JSONObject delBody = new JSONObject();
        delBody.set("id", existingId);
        HttpResponse deleted = request("POST", "/model/delete", delBody.toString());
        if (isSuccess(deleted)) {
            out.put("ok", true);
            out.put("skipped", false);
            out.put("action", "delete");
            out.put("litellmModelId", existingId);
            out.put("message", "已删除 LiteLLM 别名（block 不可用时降级）");
            return out;
        }
        out.put("ok", false);
        out.put("skipped", false);
        out.put("action", "disable");
        out.put("httpStatus", deleted.getStatus());
        out.put("message", softFailMessage("停用别名失败", deleted));
        return out;
    }

    private void unblockQuietly(String litellmModelId) {
        if (StrUtil.isBlank(litellmModelId)) {
            return;
        }
        try {
            JSONObject body = new JSONObject();
            body.set("model_id", litellmModelId);
            request("POST", "/model/unblock", body.toString());
        } catch (Exception ignored) {
            // best-effort
        }
    }

    private JSONObject buildDeploymentBody(String alias, String vendor, String upstreamModel,
                                           String apiBase, String kind, String apiKey) {
        JSONObject litellmParams = new JSONObject();
        // 自定义 api_base（含 OpenAI 兼容网关）一律走 openai/ 前缀
        if (StrUtil.isNotBlank(apiBase)) {
            String m = StrUtil.blankToDefault(upstreamModel, "").trim();
            litellmParams.set("model", m.contains("/") ? m : "openai/" + m);
            litellmParams.set("api_base", apiBase.trim());
        } else {
            litellmParams.set("model", toLitellmModel(vendor, upstreamModel));
        }
        if (StrUtil.isNotBlank(apiKey)) {
            litellmParams.set("api_key", apiKey.trim());
        } else {
            String envKey = envKeyHint(vendor);
            if (StrUtil.isNotBlank(envKey)) {
                litellmParams.set("api_key", "os.environ/" + envKey);
            }
        }
        JSONObject modelInfo = new JSONObject();
        modelInfo.set("lh_kind", StrUtil.blankToDefault(kind, "chat"));
        modelInfo.set("lh_source", "gov_ai_model");
        JSONObject body = new JSONObject();
        body.set("model_name", alias);
        body.set("litellm_params", litellmParams);
        body.set("model_info", modelInfo);
        return body;
    }

    /** 厂商 → LiteLLM 上游标识（openai/...）；自建走 openai 兼容 + api_base */
    static String toLitellmModel(String vendor, String upstreamModel) {
        String m = StrUtil.blankToDefault(upstreamModel, "").trim();
        if (m.contains("/")) {
            return m;
        }
        String v = StrUtil.blankToDefault(vendor, "").trim().toLowerCase(Locale.ROOT);
        if (v.contains("anthropic") || v.contains("claude")) {
            return "anthropic/" + m;
        }
        if (v.contains("deepseek")) {
            return "deepseek/" + m;
        }
        if (v.contains("gemini") || v.contains("google")) {
            return "gemini/" + m;
        }
        // OpenAI / 通义 / 阿里 / 自建 / 默认：OpenAI 兼容
        return "openai/" + m;
    }

    static String envKeyHint(String vendor) {
        String v = StrUtil.blankToDefault(vendor, "").trim().toLowerCase(Locale.ROOT);
        if (v.contains("anthropic") || v.contains("claude")) {
            return "ANTHROPIC_API_KEY";
        }
        if (v.contains("deepseek")) {
            return "DEEPSEEK_API_KEY";
        }
        if (v.contains("通义") || v.contains("dashscope") || v.contains("阿里") || v.contains("qwen")) {
            return "DASHSCOPE_API_KEY";
        }
        if (v.contains("openai") || v.contains("gpt")) {
            return "OPENAI_API_KEY";
        }
        if (v.contains("自建") || v.contains("local") || v.contains("vllm")) {
            return "LOCAL_LLM_API_KEY";
        }
        return "OPENAI_API_KEY";
    }

    private String findModelIdByAlias(String alias) {
        try {
            HttpResponse resp = request("GET", "/model/info", null);
            if (!isSuccess(resp)) {
                return null;
            }
            String body = resp.body();
            if (StrUtil.isBlank(body)) {
                return null;
            }
            Object parsed = JSONUtil.parse(body);
            JSONArray data;
            if (parsed instanceof JSONObject obj) {
                data = obj.getJSONArray("data");
                if (data == null) {
                    data = obj.getJSONArray("models");
                }
            } else if (parsed instanceof JSONArray arr) {
                data = arr;
            } else {
                return null;
            }
            if (data == null) {
                return null;
            }
            for (int i = 0; i < data.size(); i++) {
                JSONObject item = data.getJSONObject(i);
                if (item == null) {
                    continue;
                }
                String name = firstStr(item, "model_name", "id", "model");
                if (alias.equals(name)) {
                    String id = firstStr(item, "model_info.id", "model_info.model_id");
                    if (StrUtil.isBlank(id) && item.get("model_info") instanceof JSONObject mi) {
                        id = firstStr(mi, "id", "model_id");
                    }
                    if (StrUtil.isBlank(id)) {
                        id = firstStr(item, "model_id", "id");
                    }
                    return StrUtil.blankToDefault(id, null);
                }
            }
        } catch (Exception e) {
            log.debug("findModelIdByAlias failed: {}", e.getMessage());
        }
        return null;
    }

    private static String extractModelId(String body) {
        if (StrUtil.isBlank(body)) {
            return null;
        }
        try {
            JSONObject obj = JSONUtil.parseObj(body);
            String id = firstStr(obj, "model_id", "id");
            if (StrUtil.isBlank(id) && obj.get("model_info") instanceof JSONObject mi) {
                id = firstStr(mi, "id", "model_id");
            }
            if (StrUtil.isBlank(id) && obj.get("data") instanceof JSONObject data) {
                id = firstStr(data, "model_id", "id");
            }
            return id;
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstStr(JSONObject obj, String... keys) {
        if (obj == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key.contains(".")) {
                String[] parts = key.split("\\.", 2);
                Object nested = obj.get(parts[0]);
                if (nested instanceof JSONObject jo) {
                    String v = jo.getStr(parts[1]);
                    if (StrUtil.isNotBlank(v)) {
                        return v;
                    }
                }
                continue;
            }
            String v = obj.getStr(key);
            if (StrUtil.isNotBlank(v)) {
                return v;
            }
        }
        return null;
    }

    private static boolean isSuccess(HttpResponse resp) {
        return resp != null && resp.getStatus() >= 200 && resp.getStatus() < 300;
    }

    private static String softFailMessage(String prefix, HttpResponse resp) {
        String body = resp == null ? "" : StrUtil.maxLength(StrUtil.blankToDefault(resp.body(), ""), 180);
        int code = resp == null ? -1 : resp.getStatus();
        return prefix + " HTTP " + code
                + (StrUtil.isBlank(body) ? "" : (" · " + body))
                + "；门户登记已保存，请检查 store_model_in_db / master key";
    }

    /**
     * POST JSON；仅 HTTP 2xx 且 body 非上游 error 信封时 ok=true。
     * 非 2xx 时仍解析 body 提取 error.message，避免把错误 JSON 当 completion。
     */
    private Map<String, Object> postJsonResult(String path, String jsonBody) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            HttpResponse resp = request("POST", path, jsonBody);
            int code = resp.getStatus();
            String body = resp.body();
            out.put("httpStatus", code);
            out.put("body", body);
            if (!isSuccess(resp)) {
                out.put("ok", false);
                String fromBody = null;
                try {
                    if (StrUtil.isNotBlank(body) && JSONUtil.isTypeJSON(body)) {
                        fromBody = extractUpstreamError(JSONUtil.parseObj(body));
                    }
                } catch (Exception ignored) {
                    // keep raw
                }
                if (StrUtil.isBlank(fromBody)) {
                    fromBody = StrUtil.maxLength(StrUtil.blankToDefault(body, ""), 240);
                }
                out.put("error", "HTTP " + code
                        + (StrUtil.isBlank(fromBody) ? "" : (" · " + fromBody)));
                return out;
            }
            // 少数网关用 200 + error 信封
            if (StrUtil.isNotBlank(body) && JSONUtil.isTypeJSONObject(body)) {
                String err = extractUpstreamError(JSONUtil.parseObj(body));
                if (StrUtil.isNotBlank(err)) {
                    out.put("ok", false);
                    out.put("error", err);
                    return out;
                }
            }
            out.put("ok", true);
            return out;
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
            return out;
        }
    }

    private HttpResponse request(String method, String path, String jsonBody) {
        LhProperties.Ai ai = lhProperties.getAi();
        String base = StrUtil.removeSuffix(ai.getLitellmUrl().trim(), "/");
        HttpRequest req;
        if ("GET".equalsIgnoreCase(method)) {
            req = HttpRequest.get(base + path).timeout(12_000);
        } else {
            req = HttpRequest.post(base + path)
                    .timeout(20_000)
                    .header("Content-Type", "application/json");
            if (jsonBody != null) {
                req.body(jsonBody);
            }
        }
        if (StrUtil.isNotBlank(ai.getLitellmMasterKey())) {
            req.header("Authorization", "Bearer " + ai.getLitellmMasterKey());
        }
        return req.execute();
    }
}
