package vip.xiaonuo.lh.core.ai;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * OpenAI 兼容 Chat Completions SSE（{@code stream:true}）解析。
 */
public final class LhOpenAiSseStream {

    private static final Logger log = LoggerFactory.getLogger(LhOpenAiSseStream.class);
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private LhOpenAiSseStream() {
    }

    /**
     * @param url     完整 chat/completions URL
     * @param apiKey  Bearer；可空
     * @param bodyJson 请求体（须含 stream:true）
     * @param onDelta 每个 content 增量；可空
     * @return ok / content / error / httpStatus
     */
    public static Map<String, Object> stream(String url, String apiKey, String bodyJson,
                                             Consumer<String> onDelta) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        if (StrUtil.isBlank(url) || StrUtil.isBlank(bodyJson)) {
            out.put("error", "url / body 不完整");
            return out;
        }
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(180))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8));
            if (StrUtil.isNotBlank(apiKey)) {
                b.header("Authorization", "Bearer " + apiKey.trim());
            }
            HttpResponse<InputStream> resp = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            int code = resp.statusCode();
            out.put("httpStatus", code);
            if (code < 200 || code >= 300) {
                String errBody = readLimited(resp.body(), 800);
                out.put("error", "HTTP " + code
                        + (StrUtil.isBlank(errBody) ? "" : (" · " + StrUtil.maxLength(errBody, 240))));
                return out;
            }
            StringBuilder full = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        continue;
                    }
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String raw = line.substring(5).trim();
                    if (raw.isEmpty() || "[DONE]".equalsIgnoreCase(raw)) {
                        if ("[DONE]".equalsIgnoreCase(raw)) {
                            break;
                        }
                        continue;
                    }
                    String delta = extractDeltaContent(raw);
                    if (StrUtil.isNotBlank(delta)) {
                        full.append(delta);
                        if (onDelta != null) {
                            onDelta.accept(delta);
                        }
                    }
                }
            }
            String content = full.toString();
            if (StrUtil.isBlank(content)) {
                out.put("error", "上游流式返回空 content");
                return out;
            }
            out.put("ok", true);
            out.put("content", content);
            return out;
        } catch (Exception e) {
            log.warn("OpenAI SSE stream failed: {}", e.getMessage());
            out.put("error", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
            return out;
        }
    }

    static String extractDeltaContent(String rawJson) {
        try {
            if (!JSONUtil.isTypeJSONObject(rawJson)) {
                return null;
            }
            JSONObject root = JSONUtil.parseObj(rawJson);
            String err = LhLiteLlmClient.extractUpstreamError(root);
            if (StrUtil.isNotBlank(err)) {
                return null;
            }
            JSONArray choices = root.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                return null;
            }
            JSONObject c0 = choices.getJSONObject(0);
            if (c0 == null) {
                return null;
            }
            JSONObject delta = c0.getJSONObject("delta");
            if (delta != null && delta.containsKey("content")) {
                Object v = delta.get("content");
                return v == null ? null : String.valueOf(v);
            }
            // 少数网关把增量放在 message.content
            JSONObject msg = c0.getJSONObject("message");
            if (msg != null && msg.containsKey("content")) {
                Object v = msg.get("content");
                return v == null ? null : String.valueOf(v);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String readLimited(InputStream in, int max) {
        if (in == null) {
            return "";
        }
        try {
            byte[] buf = in.readNBytes(Math.max(64, max));
            return new String(buf, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
