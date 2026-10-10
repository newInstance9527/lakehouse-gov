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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Apache Superset REST 客户端（登录 JWT + Database / Dataset）。
 * <p>未配置 URL 或登录失败时 soft-fail（ok=false），不阻断门户主路径。</p>
 */
@Component
public class SupersetClient {

    private static final Logger log = LoggerFactory.getLogger(SupersetClient.class);

    private final AtomicReference<String> cachedToken = new AtomicReference<>();
    private volatile long tokenExpireAtMs;

    @Resource
    private LhProperties lhProperties;
    @Resource
    private LhComponentCredentialResolver credentialResolver;

    public boolean configured() {
        return StrUtil.isNotBlank(baseUrl());
    }

    public String baseUrl() {
        LhProperties.Superset c = lhProperties.getSuperset();
        return c == null ? "" : StrUtil.trim(StrUtil.blankToDefault(c.getUrl(), ""));
    }

    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("component", "superset");
        if (!configured()) {
            m.put("status", "SKIPPED");
            m.put("message", "lh.superset.url empty");
            return m;
        }
        try {
            String token = loginToken();
            if (StrUtil.isBlank(token)) {
                m.put("status", "DOWN");
                m.put("message", "login failed");
                return m;
            }
            HttpResponse resp = HttpRequest.get(baseUrl() + "/api/v1/database/?q=(page:0,page_size:1)")
                    .header("Authorization", "Bearer " + token)
                    .timeout(timeoutMs())
                    .execute();
            m.put("status", resp.isOk() ? "UP" : "DOWN");
            m.put("httpStatus", resp.getStatus());
            m.put("url", baseUrl());
            return m;
        } catch (Exception e) {
            m.put("status", "DOWN");
            m.put("error", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
            return m;
        }
    }

    /**
     * 按 database_name 查找；找不到返回 null。
     */
    public Long findDatabaseIdByName(String databaseName) {
        Map<String, Object> list = listDatabases(databaseName, 50);
        if (!Boolean.TRUE.equals(list.get("ok")) || !(list.get("result") instanceof List<?> rows)) {
            return null;
        }
        for (Object row : rows) {
            JSONObject o = JSONUtil.parseObj(row);
            if (databaseName.equals(o.getStr("database_name"))) {
                return o.getLong("id");
            }
        }
        return null;
    }

    public Map<String, Object> listDatabases(String nameHint, int pageSize) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!configured()) {
            out.put("ok", false);
            out.put("skipped", true);
            out.put("message", "superset url empty");
            return out;
        }
        try {
            String token = requireToken();
            String q = "(page:0,page_size:" + Math.max(1, Math.min(pageSize, 100)) + ")";
            HttpResponse resp = HttpRequest.get(baseUrl() + "/api/v1/database/?q=" + urlEncode(q))
                    .header("Authorization", "Bearer " + token)
                    .timeout(timeoutMs())
                    .execute();
            JSONObject body = parseBody(resp.body());
            List<Map<String, Object>> rows = new ArrayList<>();
            JSONArray arr = body.getJSONArray("result");
            if (arr != null) {
                for (int i = 0; i < arr.size(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String n = o.getStr("database_name");
                    if (StrUtil.isNotBlank(nameHint) && (n == null || !n.contains(nameHint))) {
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", o.getLong("id"));
                    row.put("database_name", n);
                    rows.add(row);
                }
            }
            out.put("ok", resp.isOk());
            out.put("httpStatus", resp.getStatus());
            out.put("result", rows);
            if (!resp.isOk()) {
                out.put("message", StrUtil.maxLength(resp.body(), 400));
            }
            return out;
        } catch (Exception e) {
            out.put("ok", false);
            out.put("degraded", true);
            out.put("message", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
            return out;
        }
    }

    /**
     * 创建或更新 Database；返回 {ok, id, message, ...}。
     */
    public Map<String, Object> upsertDatabase(Long existingId, String databaseName, String sqlalchemyUri,
                                              Map<String, Object> extra) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!configured()) {
            out.put("ok", false);
            out.put("skipped", true);
            out.put("message", "superset url empty");
            return out;
        }
        try {
            String token = requireToken();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("database_name", databaseName);
            body.put("sqlalchemy_uri", sqlalchemyUri);
            body.put("expose_in_sqllab", true);
            body.put("allow_ctas", false);
            body.put("allow_cvas", true);
            body.put("allow_dml", false);
            body.put("extra", JSONUtil.toJsonStr(extra == null ? defaultDbExtra() : extra));

            HttpResponse resp;
            if (existingId != null) {
                resp = HttpRequest.put(baseUrl() + "/api/v1/database/" + existingId)
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .body(JSONUtil.toJsonStr(body))
                        .timeout(timeoutMs())
                        .execute();
            } else {
                Long matched = findDatabaseIdByName(databaseName);
                if (matched != null) {
                    existingId = matched;
                    resp = HttpRequest.put(baseUrl() + "/api/v1/database/" + existingId)
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", "application/json")
                            .body(JSONUtil.toJsonStr(body))
                            .timeout(timeoutMs())
                            .execute();
                } else {
                    resp = HttpRequest.post(baseUrl() + "/api/v1/database/")
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", "application/json")
                            .body(JSONUtil.toJsonStr(body))
                            .timeout(timeoutMs())
                            .execute();
                }
            }
            JSONObject parsed = parseBody(resp.body());
            Long id = existingId;
            if (id == null) {
                id = parsed.getLong("id");
                if (id == null && parsed.getJSONObject("result") != null) {
                    id = parsed.getJSONObject("result").getLong("id");
                }
                if (id == null) {
                    id = findDatabaseIdByName(databaseName);
                }
            }
            boolean ok = resp.isOk() && id != null;
            out.put("ok", ok);
            out.put("httpStatus", resp.getStatus());
            out.put("id", id);
            out.put("database_name", databaseName);
            if (!ok) {
                out.put("message", StrUtil.maxLength(StrUtil.blankToDefault(parsed.getStr("message"), resp.body()), 400));
            }
            return out;
        } catch (Exception e) {
            log.warn("superset upsertDatabase failed: {}", e.getMessage());
            out.put("ok", false);
            out.put("degraded", true);
            out.put("message", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
            return out;
        }
    }

    public Long findDatasetIdByName(String tableName, Long databaseId) {
        Map<String, Object> list = listDatasets(tableName, 50);
        if (!Boolean.TRUE.equals(list.get("ok")) || !(list.get("result") instanceof List<?> rows)) {
            return null;
        }
        for (Object row : rows) {
            JSONObject o = JSONUtil.parseObj(row);
            if (!tableName.equals(o.getStr("table_name")) && !tableName.equals(o.getStr("datasource_name"))) {
                continue;
            }
            Long db = o.getLong("database_id");
            if (db == null && o.getJSONObject("database") != null) {
                db = o.getJSONObject("database").getLong("id");
            }
            if (databaseId == null || databaseId.equals(db)) {
                return o.getLong("id");
            }
        }
        return null;
    }

    public Map<String, Object> listDatasets(String nameHint, int pageSize) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!configured()) {
            out.put("ok", false);
            out.put("skipped", true);
            out.put("message", "superset url empty");
            return out;
        }
        try {
            String token = requireToken();
            String q = "(page:0,page_size:" + Math.max(1, Math.min(pageSize, 100)) + ")";
            HttpResponse resp = HttpRequest.get(baseUrl() + "/api/v1/dataset/?q=" + urlEncode(q))
                    .header("Authorization", "Bearer " + token)
                    .timeout(timeoutMs())
                    .execute();
            JSONObject body = parseBody(resp.body());
            List<Map<String, Object>> rows = new ArrayList<>();
            JSONArray arr = body.getJSONArray("result");
            if (arr != null) {
                for (int i = 0; i < arr.size(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String n = StrUtil.blankToDefault(o.getStr("table_name"), o.getStr("datasource_name"));
                    if (StrUtil.isNotBlank(nameHint) && (n == null || !n.contains(nameHint))) {
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", o.getLong("id"));
                    row.put("table_name", n);
                    Long db = o.getLong("database_id");
                    if (db == null && o.getJSONObject("database") != null) {
                        db = o.getJSONObject("database").getLong("id");
                    }
                    row.put("database_id", db);
                    rows.add(row);
                }
            }
            out.put("ok", resp.isOk());
            out.put("httpStatus", resp.getStatus());
            out.put("result", rows);
            if (!resp.isOk()) {
                out.put("message", StrUtil.maxLength(resp.body(), 400));
            }
            return out;
        } catch (Exception e) {
            out.put("ok", false);
            out.put("degraded", true);
            out.put("message", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
            return out;
        }
    }

    /**
     * 创建或更新虚拟数据集（SQL Dataset）。
     */
    public Map<String, Object> upsertVirtualDataset(Long existingId, Long databaseId, String tableName, String sql) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!configured()) {
            out.put("ok", false);
            out.put("skipped", true);
            out.put("message", "superset url empty");
            return out;
        }
        try {
            String token = requireToken();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("database", databaseId);
            body.put("schema", "");
            body.put("table_name", tableName);
            body.put("sql", sql);

            HttpResponse resp;
            if (existingId != null) {
                resp = HttpRequest.put(baseUrl() + "/api/v1/dataset/" + existingId)
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .body(JSONUtil.toJsonStr(body))
                        .timeout(timeoutMs())
                        .execute();
            } else {
                Long matched = findDatasetIdByName(tableName, databaseId);
                if (matched != null) {
                    existingId = matched;
                    resp = HttpRequest.put(baseUrl() + "/api/v1/dataset/" + existingId)
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", "application/json")
                            .body(JSONUtil.toJsonStr(body))
                            .timeout(timeoutMs())
                            .execute();
                } else {
                    resp = HttpRequest.post(baseUrl() + "/api/v1/dataset/")
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", "application/json")
                            .body(JSONUtil.toJsonStr(body))
                            .timeout(timeoutMs())
                            .execute();
                }
            }
            JSONObject parsed = parseBody(resp.body());
            Long id = existingId;
            if (id == null) {
                id = parsed.getLong("id");
                if (id == null && parsed.getJSONObject("result") != null) {
                    id = parsed.getJSONObject("result").getLong("id");
                }
                if (id == null) {
                    id = findDatasetIdByName(tableName, databaseId);
                }
            }
            boolean ok = resp.isOk() && id != null;
            out.put("ok", ok);
            out.put("httpStatus", resp.getStatus());
            out.put("id", id);
            out.put("table_name", tableName);
            if (!ok) {
                out.put("message", StrUtil.maxLength(StrUtil.blankToDefault(parsed.getStr("message"), resp.body()), 400));
            } else if (id != null) {
                // soft-fail：刷新列/指标，避免图表仍用旧 schema
                Map<String, Object> refreshed = refreshDatasetColumnsSoft(id, token);
                out.put("columnsRefreshed", refreshed.get("ok"));
                if (!Boolean.TRUE.equals(refreshed.get("ok"))) {
                    out.put("columnsRefreshMessage", refreshed.get("message"));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("superset upsertVirtualDataset failed: {}", e.getMessage());
            out.put("ok", false);
            out.put("degraded", true);
            out.put("message", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
            return out;
        }
    }

    /**
     * PUT 后触发数据集列同步（Superset 3.x：{@code /api/v1/dataset/{id}/refresh}）。
     */
    public Map<String, Object> refreshDatasetColumnsSoft(long datasetId, String bearerToken) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            String token = StrUtil.blankToDefault(bearerToken, requireToken());
            HttpResponse resp = HttpRequest.put(baseUrl() + "/api/v1/dataset/" + datasetId + "/refresh")
                    .header("Authorization", "Bearer " + token)
                    .timeout(timeoutMs())
                    .execute();
            boolean ok = resp.isOk() || resp.getStatus() == 200 || resp.getStatus() == 201;
            if (!ok && resp.getStatus() == 405) {
                // 部分版本用 POST
                resp = HttpRequest.post(baseUrl() + "/api/v1/dataset/" + datasetId + "/refresh")
                        .header("Authorization", "Bearer " + token)
                        .timeout(timeoutMs())
                        .execute();
                ok = resp.isOk() || resp.getStatus() == 200 || resp.getStatus() == 201;
            }
            out.put("ok", ok);
            out.put("httpStatus", resp.getStatus());
            if (!ok) {
                out.put("message", StrUtil.maxLength(resp.body(), 300));
                log.debug("superset dataset refresh soft-fail id={} status={}", datasetId, resp.getStatus());
            }
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
        }
        return out;
    }

    /**
     * 拉取 Dashboard 列表（分页合并；soft-fail）。
     */
    public Map<String, Object> listDashboards(int pageSize) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!configured()) {
            out.put("ok", false);
            out.put("skipped", true);
            out.put("message", "superset url empty");
            return out;
        }
        try {
            String token = requireToken();
            int size = Math.max(1, Math.min(pageSize, 100));
            List<Map<String, Object>> rows = new ArrayList<>();
            int page = 0;
            int total = Integer.MAX_VALUE;
            int maxPages = 20;
            while (page < maxPages && rows.size() < total) {
                String q = "(page:" + page + ",page_size:" + size
                        + ",order_column:changed_on_delta_humanized,order_direction:desc)";
                HttpResponse resp = HttpRequest.get(baseUrl() + "/api/v1/dashboard/?q=" + urlEncode(q))
                        .header("Authorization", "Bearer " + token)
                        .timeout(timeoutMs())
                        .execute();
                if (!resp.isOk()) {
                    out.put("ok", false);
                    out.put("httpStatus", resp.getStatus());
                    out.put("result", rows);
                    out.put("count", rows.size());
                    out.put("message", StrUtil.maxLength(resp.body(), 400));
                    return out;
                }
                JSONObject body = parseBody(resp.body());
                total = body.getInt("count", rows.size());
                JSONArray arr = body.getJSONArray("result");
                if (arr == null || arr.isEmpty()) {
                    break;
                }
                for (int i = 0; i < arr.size(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", o.getLong("id"));
                    row.put("dashboard_title", o.getStr("dashboard_title"));
                    row.put("slug", o.getStr("slug"));
                    row.put("published", o.getBool("published", true));
                    row.put("url", o.getStr("url"));
                    row.put("changed_on_utc", o.getStr("changed_on_utc"));
                    rows.add(row);
                }
                if (arr.size() < size) {
                    break;
                }
                page++;
            }
            out.put("ok", true);
            out.put("result", rows);
            out.put("count", total == Integer.MAX_VALUE ? rows.size() : total);
            out.put("pages", page + 1);
            return out;
        } catch (Exception e) {
            out.put("ok", false);
            out.put("degraded", true);
            out.put("message", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
            return out;
        }
    }

    /** 浏览器侧 Domain（Embedded SDK）；可与内网 API url 分离 */
    public String publicBaseUrl() {
        LhProperties.Superset c = lhProperties.getSuperset();
        if (c == null) {
            return "";
        }
        String pub = StrUtil.trim(StrUtil.blankToDefault(c.getPublicUrl(), ""));
        if (StrUtil.isNotBlank(pub)) {
            return StrUtil.removeSuffix(pub, "/");
        }
        return StrUtil.removeSuffix(baseUrl(), "/");
    }

    public boolean embedEnabled() {
        LhProperties.Superset c = lhProperties.getSuperset();
        return c != null && c.isEmbedEnabled() && configured();
    }

    public boolean oidcEnabled() {
        LhProperties.Superset c = lhProperties.getSuperset();
        return c != null && c.isOidcEnabled() && configured();
    }

    /**
     * 确保 Dashboard 已启用 Embedded，返回 uuid。
     */
    public Map<String, Object> ensureDashboardEmbedded(long dashboardId, List<String> allowedDomains) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!embedEnabled()) {
            out.put("ok", false);
            out.put("skipped", true);
            out.put("message", "lh.superset.embed-enabled=false 或未配置 url");
            return out;
        }
        try {
            String token = requireToken();
            // GET 已有
            HttpResponse get = HttpRequest.get(baseUrl() + "/api/v1/dashboard/" + dashboardId + "/embedded")
                    .header("Authorization", "Bearer " + token)
                    .timeout(timeoutMs())
                    .execute();
            if (get.isOk()) {
                JSONObject body = parseBody(get.body());
                JSONObject result = body.getJSONObject("result");
                if (result == null) {
                    result = body;
                }
                String uuid = result.getStr("uuid");
                if (StrUtil.isBlank(uuid)) {
                    uuid = body.getStr("uuid");
                }
                if (StrUtil.isNotBlank(uuid)) {
                    // 刷新 allowed_domains
                    Map<String, Object> putBody = new LinkedHashMap<>();
                    putBody.put("allowed_domains", allowedDomains == null ? List.of() : allowedDomains);
                    HttpRequest.post(baseUrl() + "/api/v1/dashboard/" + dashboardId + "/embedded")
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", "application/json")
                            .body(JSONUtil.toJsonStr(putBody))
                            .timeout(timeoutMs())
                            .execute();
                    out.put("ok", true);
                    out.put("uuid", uuid);
                    out.put("dashboardId", dashboardId);
                    return out;
                }
            }
            Map<String, Object> postBody = new LinkedHashMap<>();
            postBody.put("allowed_domains", allowedDomains == null ? List.of() : allowedDomains);
            HttpResponse post = HttpRequest.post(baseUrl() + "/api/v1/dashboard/" + dashboardId + "/embedded")
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .body(JSONUtil.toJsonStr(postBody))
                    .timeout(timeoutMs())
                    .execute();
            JSONObject parsed = parseBody(post.body());
            JSONObject result = parsed.getJSONObject("result");
            String uuid = result != null ? result.getStr("uuid") : parsed.getStr("uuid");
            boolean ok = post.isOk() && StrUtil.isNotBlank(uuid);
            out.put("ok", ok);
            out.put("httpStatus", post.getStatus());
            out.put("uuid", uuid);
            out.put("dashboardId", dashboardId);
            if (!ok) {
                out.put("message", StrUtil.maxLength(StrUtil.blankToDefault(parsed.getStr("message"), post.body()), 500));
            }
            return out;
        } catch (Exception e) {
            log.warn("superset ensureDashboardEmbedded failed: {}", e.getMessage());
            out.put("ok", false);
            out.put("degraded", true);
            out.put("message", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
            return out;
        }
    }

    /**
     * 签发 Guest Token（资源 id = embedded uuid）。
     */
    public Map<String, Object> createGuestToken(String username, String firstName, String lastName,
                                                String embeddedUuid, List<Map<String, Object>> rls) {
        return createGuestToken(username, firstName, lastName, embeddedUuid, rls, null);
    }

    /**
     * 签发 Guest Token；可选 {@code datasets} allowlist（新版本 Superset 支持；不支持则自动去掉重试）。
     */
    public Map<String, Object> createGuestToken(String username, String firstName, String lastName,
                                                String embeddedUuid, List<Map<String, Object>> rls,
                                                List<Long> datasetAllowlist) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!embedEnabled()) {
            out.put("ok", false);
            out.put("message", "embed disabled");
            return out;
        }
        if (StrUtil.isBlank(embeddedUuid)) {
            out.put("ok", false);
            out.put("message", "embeddedUuid empty");
            return out;
        }
        try {
            String token = requireToken();
            Map<String, Object> user = new LinkedHashMap<>();
            user.put("username", StrUtil.blankToDefault(username, "portal_embed"));
            user.put("first_name", StrUtil.blankToDefault(firstName, "Portal"));
            user.put("last_name", StrUtil.blankToDefault(lastName, "User"));

            Map<String, Object> resource = new LinkedHashMap<>();
            resource.put("type", "dashboard");
            resource.put("id", embeddedUuid);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("user", user);
            body.put("resources", List.of(resource));
            body.put("rls", rls == null ? List.of() : rls);
            boolean wantAllowlist = datasetAllowlist != null && !datasetAllowlist.isEmpty();
            if (wantAllowlist) {
                body.put("datasets", datasetAllowlist.stream().map(Long::intValue).toList());
            }

            Map<String, Object> attempt = postGuestToken(token, body);
            if (!Boolean.TRUE.equals(attempt.get("ok")) && wantAllowlist) {
                // 3.1.x 等旧版无 datasets 字段 → 去掉 allowlist 重试，依赖 dataset 级 RLS / 1=0
                body.remove("datasets");
                Map<String, Object> retry = postGuestToken(token, body);
                retry.put("datasetsAllowlistApplied", false);
                retry.put("datasetsAllowlistSkipped", true);
                retry.put("datasetsAllowlistSkipReason", attempt.get("message"));
                retry.put("embeddedUuid", embeddedUuid);
                return retry;
            }
            attempt.put("datasetsAllowlistApplied", wantAllowlist);
            attempt.put("embeddedUuid", embeddedUuid);
            return attempt;
        } catch (Exception e) {
            log.warn("superset createGuestToken failed: {}", e.getMessage());
            out.put("ok", false);
            out.put("degraded", true);
            out.put("message", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
            return out;
        }
    }

    private Map<String, Object> postGuestToken(String bearer, Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        HttpResponse resp = HttpRequest.post(baseUrl() + "/api/v1/security/guest_token/")
                .header("Authorization", "Bearer " + bearer)
                .header("Content-Type", "application/json")
                .body(JSONUtil.toJsonStr(body))
                .timeout(timeoutMs())
                .execute();
        JSONObject parsed = parseBody(resp.body());
        String guest = parsed.getStr("token");
        if (StrUtil.isBlank(guest) && parsed.getJSONObject("result") != null) {
            guest = parsed.getJSONObject("result").getStr("token");
        }
        if (StrUtil.isBlank(guest)) {
            guest = parsed.getStr("guest_token");
        }
        boolean ok = resp.isOk() && StrUtil.isNotBlank(guest);
        out.put("ok", ok);
        out.put("httpStatus", resp.getStatus());
        out.put("token", guest);
        if (!ok) {
            out.put("message", StrUtil.maxLength(StrUtil.blankToDefault(parsed.getStr("message"), resp.body()), 500));
        }
        return out;
    }

    /**
     * 解析看板关联的 dataset id（charts API soft-fail → 空列表）。
     */
    public List<Long> listDashboardDatasetIds(long dashboardId) {
        Set<Long> ids = new LinkedHashSet<>();
        if (!configured() || dashboardId <= 0) {
            return List.of();
        }
        try {
            String token = requireToken();
            HttpResponse resp = HttpRequest.get(baseUrl() + "/api/v1/dashboard/" + dashboardId + "/charts")
                    .header("Authorization", "Bearer " + token)
                    .timeout(timeoutMs())
                    .execute();
            if (resp.isOk()) {
                collectDatasetIds(parseBody(resp.body()), ids);
            }
            if (ids.isEmpty()) {
                HttpResponse dash = HttpRequest.get(baseUrl() + "/api/v1/dashboard/" + dashboardId)
                        .header("Authorization", "Bearer " + token)
                        .timeout(timeoutMs())
                        .execute();
                if (dash.isOk()) {
                    collectDatasetIds(parseBody(dash.body()), ids);
                }
            }
        } catch (Exception e) {
            log.debug("superset listDashboardDatasetIds soft-fail id={}: {}", dashboardId, e.getMessage());
        }
        return new ArrayList<>(ids);
    }

    private static void collectDatasetIds(JSONObject root, Set<Long> ids) {
        if (root == null || ids == null) {
            return;
        }
        Object result = root.get("result");
        if (result instanceof JSONArray arr) {
            for (Object o : arr) {
                if (o instanceof JSONObject jo) {
                    extractDatasetId(jo, ids);
                }
            }
        } else if (result instanceof JSONObject jo) {
            extractDatasetId(jo, ids);
            Object charts = jo.get("charts");
            if (charts instanceof JSONArray arr) {
                for (Object o : arr) {
                    if (o instanceof JSONObject c) {
                        extractDatasetId(c, ids);
                    }
                }
            }
        }
    }

    private static void extractDatasetId(JSONObject jo, Set<Long> ids) {
        if (jo == null) {
            return;
        }
        Object formData = jo.get("form_data");
        JSONObject fd = null;
        if (formData instanceof JSONObject f) {
            fd = f;
        } else if (formData instanceof String s && StrUtil.isNotBlank(s)) {
            try {
                fd = JSONUtil.parseObj(s);
            } catch (Exception ignored) {
                /* skip */
            }
        }
        if (fd != null) {
            addDatasourceId(fd.get("datasource"), ids);
            addDatasourceId(fd.get("datasource_id"), ids);
        }
        addDatasourceId(jo.get("datasource_id"), ids);
        Object slice = jo.get("slice");
        if (slice instanceof JSONObject sl) {
            extractDatasetId(sl, ids);
        }
    }

    private static void addDatasourceId(Object raw, Set<Long> ids) {
        if (raw == null || ids == null) {
            return;
        }
        if (raw instanceof Number n) {
            if (n.longValue() > 0) {
                ids.add(n.longValue());
            }
            return;
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) {
            return;
        }
        // "12__table" / "12"
        int cut = s.indexOf('_');
        String num = cut > 0 ? s.substring(0, cut) : s;
        try {
            long id = Long.parseLong(num);
            if (id > 0) {
                ids.add(id);
            }
        } catch (NumberFormatException ignored) {
            /* skip */
        }
    }

    /**
     * OIDC 做表入口（带 next 回落）。未启用 OIDC 时 ok=false，禁止 AUTH_DB 裸链降级。
     */
    public Map<String, Object> authoringEntry(String kind, String id) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!configured()) {
            out.put("ok", false);
            out.put("message", "未配置 lh.superset.url");
            return out;
        }
        if (!oidcEnabled()) {
            out.put("ok", false);
            out.put("oidcRequired", true);
            out.put("message", "做表须启用 Superset OIDC（lh.superset.oidc-enabled=true，与门户同 IdP）；已禁止 AUTH_DB 裸深链");
            return out;
        }
        String base = publicBaseUrl();
        String k = StrUtil.blankToDefault(kind, "home").toLowerCase();
        String next = switch (k) {
            case "dashboard" -> "/superset/dashboard/" + StrUtil.blankToDefault(id, "") + "/";
            case "dataset", "explore" -> "/explore/?datasource_type=table&datasource_id=" + StrUtil.blankToDefault(id, "");
            case "sqllab" -> "/sqllab/";
            default -> "/superset/welcome/";
        };
        LhProperties.Superset c = lhProperties.getSuperset();
        String loginPath = c == null ? "/login/" : StrUtil.blankToDefault(c.getOidcLoginPath(), "/login/");
        if (!loginPath.startsWith("http")) {
            loginPath = base + (loginPath.startsWith("/") ? loginPath : "/" + loginPath);
        }
        String url = loginPath + (loginPath.contains("?") ? "&" : "?") + "next=" + urlEncode(next);
        out.put("ok", true);
        out.put("url", url);
        out.put("next", next);
        out.put("mode", "oidc");
        return out;
    }

    /** @deprecated 看数请用 Guest Token 内嵌；仅内部拼 next 用 */
    public String deeplink(String kind, String id) {
        return deeplink(kind, id, null);
    }

    public String deeplink(String kind, String id, Map<String, Object> dims) {
        String base = publicBaseUrl();
        if (StrUtil.isBlank(base)) {
            return "";
        }
        String k = StrUtil.blankToDefault(kind, "home").toLowerCase();
        String url = switch (k) {
            case "dataset", "explore" -> base + "/explore/?datasource_type=table&datasource_id="
                    + StrUtil.blankToDefault(id, "");
            case "dashboard" -> base + "/superset/dashboard/" + StrUtil.blankToDefault(id, "") + "/";
            case "sqllab" -> base + "/sqllab/";
            default -> base + "/superset/welcome/";
        };
        if (dims != null && !dims.isEmpty()) {
            url = url + (url.contains("?") ? "&" : "?") + "lh_dims=" + urlEncode(JSONUtil.toJsonStr(dims));
        }
        return url;
    }

    private Map<String, Object> defaultDbExtra() {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("allows_virtual_table_explore", true);
        extra.put("engine_params", Map.of("connect_args", Map.of("http_scheme", "http")));
        return extra;
    }

    private String requireToken() {
        String token = loginToken();
        if (StrUtil.isBlank(token)) {
            throw new IllegalStateException("superset login failed");
        }
        return token;
    }

    private String loginToken() {
        long now = System.currentTimeMillis();
        String cached = cachedToken.get();
        if (StrUtil.isNotBlank(cached) && now < tokenExpireAtMs - 30_000L) {
            return cached;
        }
        if (!configured()) {
            return null;
        }
        Map<String, String> cred = credentialResolver.superset();
        String user = StrUtil.blankToDefault(cred.get("username"), cred.get("user"));
        String pass = cred.get("password");
        if (StrUtil.isBlank(user) || StrUtil.isBlank(pass)) {
            log.warn("superset credentials empty");
            return null;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("username", user);
            body.put("password", pass);
            body.put("provider", "db");
            body.put("refresh", true);
            HttpResponse resp = HttpRequest.post(baseUrl() + "/api/v1/security/login")
                    .header("Content-Type", "application/json")
                    .body(JSONUtil.toJsonStr(body))
                    .timeout(timeoutMs())
                    .execute();
            if (!resp.isOk()) {
                log.warn("superset login http {}: {}", resp.getStatus(), StrUtil.maxLength(resp.body(), 200));
                cachedToken.set(null);
                return null;
            }
            JSONObject parsed = parseBody(resp.body());
            String token = parsed.getStr("access_token");
            if (StrUtil.isBlank(token) && parsed.getJSONObject("result") != null) {
                token = parsed.getJSONObject("result").getStr("access_token");
            }
            if (StrUtil.isBlank(token)) {
                return null;
            }
            cachedToken.set(token);
            tokenExpireAtMs = now + 50 * 60_000L;
            return token;
        } catch (Exception e) {
            log.warn("superset login error: {}", e.getMessage());
            cachedToken.set(null);
            return null;
        }
    }

    private int timeoutMs() {
        return 20_000;
    }

    private static JSONObject parseBody(String body) {
        if (StrUtil.isBlank(body)) {
            return new JSONObject();
        }
        try {
            return JSONUtil.parseObj(body);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
