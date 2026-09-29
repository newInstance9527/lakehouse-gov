package vip.xiaonuo.lh.core.engine;

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
import vip.xiaonuo.lh.core.vault.LhComponentCredentialResolver;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 夜莺（Nightingale / n9e）当前告警事件客户端。
 * <p>未配置 URL 或登录失败时返回空列表（soft-fail，不阻断门户）。
 */
@Component
public class NightingaleClient {

    private static final Logger log = LoggerFactory.getLogger(NightingaleClient.class);
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final AtomicReference<String> cachedToken = new AtomicReference<>();
    private volatile long tokenExpireAtMs;

    @Resource
    private LhProperties lhProperties;
    @Resource
    private LhComponentCredentialResolver credentialResolver;

    /**
     * 推送一条平台事件到夜莺侧（webhook 或 n9e event/push）。
     * <p>未配置 URL / 开关关闭 / 失败时 soft-fail，不抛异常。</p>
     *
     * @param event 至少含 title、message、severity（P0/P1/P2）
     * @return 结果 map：pushed / skipped / degraded / message / httpStatus
     */
    public Map<String, Object> pushEvent(Map<String, Object> event) {
        Map<String, Object> out = new LinkedHashMap<>();
        LhProperties.Observability o = lhProperties.getObservability();
        if (o == null || !o.isNightingalePushEnabled()) {
            out.put("pushed", false);
            out.put("skipped", true);
            out.put("message", "nightingale push disabled");
            return out;
        }
        String webhook = StrUtil.trim(o.getNightingaleWebhookUrl());
        String title = firstNonBlank(str(event.get("title")), "湖仓平台告警");
        String message = firstNonBlank(str(event.get("message")), title);
        String sev = firstNonBlank(str(event.get("severity")), "P1");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("message", message);
        body.put("severity", sev);
        body.put("labels", event.getOrDefault("labels", Map.of()));
        body.put("annotations", event.getOrDefault("annotations", Map.of()));
        body.put("opsPath", event.get("opsPath"));
        body.put("source", firstNonBlank(str(event.get("source")), "lakehouse-gov"));
        body.put("ts", System.currentTimeMillis());

        String endpoint;
        boolean useBearer = false;
        if (StrUtil.isNotBlank(webhook)) {
            endpoint = webhook;
        } else if (configured()) {
            endpoint = baseUrl() + "/v1/n9e/event/push";
            useBearer = true;
        } else {
            out.put("pushed", false);
            out.put("skipped", true);
            out.put("message", "nightingale url/webhook empty");
            return out;
        }
        try {
            HttpRequest req = HttpRequest.post(endpoint)
                    .header("Content-Type", "application/json")
                    .body(JSONUtil.toJsonStr(body))
                    .timeout(timeoutMs());
            if (useBearer) {
                String token = loginToken();
                if (StrUtil.isBlank(token)) {
                    out.put("pushed", false);
                    out.put("degraded", true);
                    out.put("message", "nightingale login failed");
                    return out;
                }
                req.header("Authorization", "Bearer " + token).header("X-User-Token", token);
            }
            HttpResponse resp = req.execute();
            out.put("httpStatus", resp.getStatus());
            if (resp.getStatus() >= 200 && resp.getStatus() < 300) {
                out.put("pushed", true);
                out.put("message", "ok");
            } else {
                out.put("pushed", false);
                out.put("degraded", true);
                out.put("message", "HTTP " + resp.getStatus() + " " + StrUtil.maxLength(resp.body(), 120));
                log.warn("nightingale pushEvent HTTP {}: {}", resp.getStatus(), StrUtil.maxLength(resp.body(), 200));
            }
        } catch (Exception e) {
            out.put("pushed", false);
            out.put("degraded", true);
            out.put("message", e.getMessage());
            log.warn("nightingale pushEvent error: {}", e.getMessage());
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    public boolean configured() {
        return StrUtil.isNotBlank(baseUrl());
    }

    /**
     * 拉当前告警，投影为 infra 告警行：sev/t/time/host/act/live/source。
     */
    public List<Map<String, Object>> listCurrentAlerts() {
        if (!configured()) {
            return List.of();
        }
        String token = loginToken();
        if (StrUtil.isBlank(token)) {
            return List.of();
        }
        int limit = alertLimit();
        int timeout = timeoutMs();
        String endpoint = baseUrl() + "/api/n9e/alert-cur-events/list";
        try {
            HttpResponse resp = HttpRequest.post(endpoint)
                    .header("Authorization", "Bearer " + token)
                    .header("X-User-Token", token)
                    .header("Content-Type", "application/json")
                    .body(JSONUtil.toJsonStr(Map.of("p", 1, "limit", limit, "pagesize", limit)))
                    .timeout(timeout)
                    .execute();
            if (resp.getStatus() == 401 || resp.getStatus() == 403) {
                cachedToken.set(null);
                token = loginToken(true);
                if (StrUtil.isBlank(token)) {
                    return List.of();
                }
                resp = HttpRequest.post(endpoint)
                        .header("Authorization", "Bearer " + token)
                        .header("X-User-Token", token)
                        .header("Content-Type", "application/json")
                        .body(JSONUtil.toJsonStr(Map.of("p", 1, "limit", limit, "pagesize", limit)))
                        .timeout(timeout)
                        .execute();
            }
            if (resp.getStatus() < 200 || resp.getStatus() >= 300) {
                log.warn("nightingale alert-cur-events HTTP {}: {}",
                        resp.getStatus(), StrUtil.maxLength(resp.body(), 200));
                return List.of();
            }
            return parseAlertList(resp.body());
        } catch (Exception e) {
            log.warn("nightingale alert-cur-events error: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> parseAlertList(String body) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (StrUtil.isBlank(body)) {
            return out;
        }
        JSONObject root = JSONUtil.parseObj(body);
        String err = root.getStr("err");
        if (StrUtil.isNotBlank(err) && !"null".equalsIgnoreCase(err)) {
            log.warn("nightingale alert list err={}", err);
            return out;
        }
        Object dat = root.get("dat");
        JSONArray list = null;
        if (dat instanceof JSONObject datObj) {
            list = datObj.getJSONArray("list");
            if (list == null) {
                list = datObj.getJSONArray("items");
            }
        } else if (dat instanceof JSONArray arr) {
            list = arr;
        }
        if (list == null || list.isEmpty()) {
            return out;
        }
        for (int i = 0; i < list.size(); i++) {
            JSONObject ev = list.getJSONObject(i);
            if (ev == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sev", mapSeverity(ev));
            row.put("t", firstNonBlank(ev.getStr("rule_name"), ev.getStr("ruleName"), "夜莺告警"));
            long triggerSec = firstLong(ev, "trigger_time", "triggerTime", "first_trigger_time", "last_eval_time");
            row.put("time", triggerSec > 0 ? TIME_FMT.format(Instant.ofEpochSecond(triggerSec)) : "—");
            row.put("host", firstNonBlank(
                    ev.getStr("target_ident"),
                    ev.getStr("targetIdent"),
                    ev.getStr("group_name"),
                    ev.getStr("cluster"),
                    "-"));
            row.put("act", "链路影响→");
            row.put("live", true);
            row.put("source", "n9e");
            row.put("domain", firstNonBlank(tagValue(ev, "domain"), "infra"));
            String title = String.valueOf(row.get("t"));
            if (title.contains("磁盘") || title.contains("容量") || title.contains("TTF")
                    || title.contains("bucket") || title.contains("存储")
                    || title.toLowerCase().contains("storage")) {
                row.put("act", "存储趋势→");
            }
            out.add(row);
            if (out.size() >= alertLimit()) {
                break;
            }
        }
        return out;
    }

    private String loginToken() {
        return loginToken(false);
    }

    private synchronized String loginToken(boolean force) {
        long now = System.currentTimeMillis();
        String cached = cachedToken.get();
        if (!force && StrUtil.isNotBlank(cached) && now < tokenExpireAtMs) {
            return cached;
        }
        Map<String, String> cred = credentialResolver.nightingale();
        String user = cred.get("username");
        String pass = cred.get("password");
        if (StrUtil.isBlank(user) || StrUtil.isBlank(pass)) {
            log.debug("nightingale credentials empty; skip login");
            return null;
        }
        String endpoint = baseUrl() + "/api/n9e/auth/login";
        try {
            HttpResponse resp = HttpRequest.post(endpoint)
                    .header("Content-Type", "application/json")
                    .body(JSONUtil.toJsonStr(Map.of("username", user, "password", pass)))
                    .timeout(timeoutMs())
                    .execute();
            if (resp.getStatus() < 200 || resp.getStatus() >= 300) {
                log.warn("nightingale login HTTP {}", resp.getStatus());
                return null;
            }
            JSONObject root = JSONUtil.parseObj(resp.body());
            String err = root.getStr("err");
            if (StrUtil.isNotBlank(err) && !"null".equalsIgnoreCase(err)) {
                log.warn("nightingale login err={}", err);
                return null;
            }
            JSONObject dat = root.getJSONObject("dat");
            if (dat == null) {
                return null;
            }
            String token = firstNonBlank(dat.getStr("access_token"), dat.getStr("token"));
            if (StrUtil.isBlank(token)) {
                return null;
            }
            cachedToken.set(token);
            // 默认缓存 50 分钟（夜莺 token 常 2h）
            tokenExpireAtMs = now + 50L * 60_000L;
            return token;
        } catch (Exception e) {
            log.warn("nightingale login error: {}", e.getMessage());
            return null;
        }
    }

    private String baseUrl() {
        LhProperties.Observability o = lhProperties.getObservability();
        if (o == null || StrUtil.isBlank(o.getNightingaleUrl())) {
            return "";
        }
        String u = o.getNightingaleUrl().trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    private int timeoutMs() {
        LhProperties.Observability o = lhProperties.getObservability();
        return o == null ? 5000 : Math.max(2000, o.getNightingaleTimeoutMs());
    }

    private int alertLimit() {
        LhProperties.Observability o = lhProperties.getObservability();
        return o == null ? 50 : Math.max(1, Math.min(200, o.getNightingaleAlertLimit()));
    }

    private static String mapSeverity(JSONObject ev) {
        Object sev = ev.get("severity");
        if (sev instanceof Number n) {
            int v = n.intValue();
            if (v <= 1) {
                return "P0";
            }
            if (v == 2) {
                return "P1";
            }
            return "P2";
        }
        String s = String.valueOf(sev == null ? "" : sev).toLowerCase();
        if (s.contains("critical") || s.contains("p0") || "1".equals(s) || "emergency".equals(s)) {
            return "P0";
        }
        if (s.contains("warning") || s.contains("p1") || "2".equals(s)) {
            return "P1";
        }
        String fromTag = tagValue(ev, "severity");
        if (StrUtil.isNotBlank(fromTag)) {
            String t = fromTag.toLowerCase();
            if (t.contains("critical")) {
                return "P0";
            }
            if (t.contains("warning")) {
                return "P1";
            }
        }
        return "P2";
    }

    private static String tagValue(JSONObject ev, String key) {
        Object tags = ev.get("tags");
        if (tags instanceof JSONArray arr) {
            for (int i = 0; i < arr.size(); i++) {
                String raw = arr.getStr(i);
                if (StrUtil.isBlank(raw)) {
                    continue;
                }
                if (raw.startsWith(key + "=")) {
                    return raw.substring(key.length() + 1);
                }
            }
        }
        return null;
    }

    private static long firstLong(JSONObject ev, String... keys) {
        for (String k : keys) {
            Long v = ev.getLong(k);
            if (v != null && v > 0) {
                // 毫秒 → 秒
                return v > 10_000_000_000L ? v / 1000L : v;
            }
        }
        return 0L;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (StrUtil.isNotBlank(v) && !"null".equalsIgnoreCase(v)) {
                return v;
            }
        }
        return null;
    }
}
