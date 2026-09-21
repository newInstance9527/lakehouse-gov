/*
 * Copyright [2022] [https://www.xiaonuo.vip]
 *
 * Snowy采用APACHE LICENSE 2.0开源协议，您在使用过程中，需要注意以下几点：
 *
 * 1.请不要删除和修改根目录下的LICENSE文件。
 * 2.请不要删除和修改Snowy源码头部的版权声明。
 * 3.本项目代码可免费商业使用，商业使用请保留源码和相关描述文件的项目出处，作者声明等。
 * 4.分发源码时候，请注明软件出处 https://www.xiaonuo.vip
 * 5.不可二次分发开源参与同类竞品，如有想法可联系团队xiaonuobase@qq.com商议合作。
 * 6.若您的项目无法满足以上几点，需要更多功能代码，获取Snowy商业授权许可，请在官网购买授权，地址为 https://www.xiaonuo.vip
 */
package vip.xiaonuo.lh.core.engine;

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
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dromara SQLREST Manager 客户端（API 定义 SoT；试跑/发版/上线）
 *
 * @author lakehouse
 * @date 2026/9/21
 */
@Component
public class SqlrestClient {

    private static final Pattern MUSTACHE = Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*}}");
    private static final long TOKEN_SKEW_MS = 60_000L;

    @Resource
    private LhProperties lhProperties;

    private final AtomicReference<CachedToken> tokenRef = new AtomicReference<>();

    public String embedUrl() {
        return StrUtil.blankToDefault(trim(lhProperties.getSqlrest().getManagerUrl()),
                "http://127.0.0.1:18090");
    }

    public Long defaultDatasourceId() {
        return lhProperties.getSqlrest().getDatasourceId();
    }

    public String executorUpstream() {
        return StrUtil.blankToDefault(lhProperties.getSqlrest().getExecutorUpstream(), "127.0.0.1:18091");
    }

    /** gateway | apisix | both；默认 gateway */
    public String edgeMode() {
        String m = StrUtil.blankToDefault(lhProperties.getSqlrest().getEdgeMode(), "gateway").trim().toLowerCase();
        if (!"gateway".equals(m) && !"apisix".equals(m) && !"both".equals(m)) {
            return "gateway";
        }
        return m;
    }

    public boolean useSqlrestGateway() {
        String m = edgeMode();
        return "gateway".equals(m) || "both".equals(m);
    }

    public boolean useApisixEdge() {
        String m = edgeMode();
        return "apisix".equals(m) || "both".equals(m);
    }

    public String gatewayUrl() {
        return trim(StrUtil.blankToDefault(lhProperties.getSqlrest().getGatewayUrl(),
                "http://127.0.0.1:18091"));
    }

    /**
     * 将门户 {{param}} 转为 SQLREST MyBatis #{param}
     */
    public static String toSqlrestSql(String sql) {
        if (StrUtil.isBlank(sql)) {
            return sql;
        }
        Matcher m = MUSTACHE.matcher(sql);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement("#{" + m.group(1) + "}"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 对外路径 → SQLREST path（去掉前导 /api/）
     */
    public static String toSqlrestPath(String publicPath) {
        String p = StrUtil.blankToDefault(publicPath, "").trim();
        if (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.startsWith("api/")) {
            p = p.substring(4);
        }
        return p;
    }

    public Map<String, Object> createAssignment(Map<String, Object> body) {
        return postJson("/sqlrest/manager/api/v1/assignment/create", body);
    }

    public Map<String, Object> updateAssignment(Map<String, Object> body) {
        return postJson("/sqlrest/manager/api/v1/assignment/update", body);
    }

    public Map<String, Object> debug(Map<String, Object> body) {
        return postJson("/sqlrest/manager/api/v1/assignment/debug", body);
    }

    /** 兼容旧 trial(apiId)：拉详情后用其 SQL 调试 */
    public Map<String, Object> trial(String sqlrestApiId) {
        try {
            Map<String, Object> detail = detail(sqlrestApiId);
            if (Boolean.TRUE.equals(detail.get("degraded")) || detail.get("data") == null) {
                return detail;
            }
            JSONObject data = JSONUtil.parseObj(detail.get("data"));
            List<String> sqls = new ArrayList<>();
            JSONArray sqlList = data.getJSONArray("sqlList");
            if (sqlList != null) {
                for (int i = 0; i < sqlList.size(); i++) {
                    sqls.add(sqlList.getJSONObject(i).getStr("sqlText"));
                }
            }
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("dataSourceId", data.getLong("datasourceId", defaultDatasourceId()));
            req.put("engine", StrUtil.blankToDefault(data.getStr("engine"), "SQL"));
            req.put("namingStrategy", StrUtil.blankToDefault(data.getStr("namingStrategy"), "CAMEL_CASE"));
            req.put("formatMap", data.get("formatMap") != null ? data.get("formatMap") : List.of());
            req.put("contextList", sqls);
            req.put("paramValues", List.of());
            return debug(req);
        } catch (Exception e) {
            return degraded("SQLREST trial: " + e.getMessage());
        }
    }

    public Map<String, Object> publish(long apiId, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", apiId);
        body.put("description", StrUtil.blankToDefault(description, "lakehouse publish"));
        return putJson("/sqlrest/manager/api/v1/assignment/publish", body);
    }

    public Map<String, Object> listVersions(long apiId) {
        return getJson("/sqlrest/manager/api/v1/version/list/" + apiId);
    }

    public Map<String, Object> deploy(long apiId, long commitId) {
        return putJson("/sqlrest/manager/api/v1/assignment/deploy/" + apiId + "?commitId=" + commitId, Map.of());
    }

    public Map<String, Object> retire(long apiId) {
        return putJson("/sqlrest/manager/api/v1/assignment/retire/" + apiId, Map.of());
    }

    public Map<String, Object> detail(String sqlrestApiId) {
        return getJson("/sqlrest/manager/api/v1/assignment/detail/" + sqlrestApiId);
    }

    public Map<String, Object> listAssignments(String searchText, int page, int size) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groupId", null);
        body.put("moduleId", null);
        body.put("online", null);
        body.put("open", null);
        body.put("searchText", StrUtil.nullToDefault(searchText, ""));
        body.put("page", page);
        body.put("size", size);
        return postJson("/sqlrest/manager/api/v1/assignment/list", body);
    }

    public Map<String, Object> listDatasources(String searchText, int page, int size) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("searchText", StrUtil.nullToDefault(searchText, ""));
        body.put("page", page);
        body.put("size", size);
        return postJson("/sqlrest/manager/api/v1/datasource/list", body);
    }

    public Map<String, Object> listDatasourceNames() {
        return getJson("/sqlrest/manager/api/v1/datasource/list/name");
    }

    public Map<String, Object> listDrivers(String sqlrestType) {
        return getJson("/sqlrest/manager/api/v1/datasource/" + sqlrestType + "/drivers");
    }

    public Map<String, Object> createDatasource(Map<String, Object> body) {
        return postJson("/sqlrest/manager/api/v1/datasource/create", body);
    }

    public Map<String, Object> updateDatasource(Map<String, Object> body) {
        return postJson("/sqlrest/manager/api/v1/datasource/update", body);
    }

    public Map<String, Object> deleteDatasource(long id) {
        return deleteJson("/sqlrest/manager/api/v1/datasource/delete/" + id);
    }

    public Map<String, Object> overviewCounter() {
        return getJson("/sqlrest/manager/api/v1/overview/counter");
    }

    public Map<String, Object> overviewTrend(int days) {
        return getJson("/sqlrest/manager/api/v1/overview/trend/" + Math.max(1, days));
    }

    public Map<String, Object> overviewTopPath(int days, int n) {
        return getJson("/sqlrest/manager/api/v1/overview/top/path/" + Math.max(1, days) + "?n=" + Math.max(1, n));
    }

    public Map<String, Object> listClients() {
        return postJson("/sqlrest/manager/api/v1/client/list", Map.of());
    }

    public Map<String, Object> listAuthGroups() {
        return postJson("/sqlrest/manager/api/v1/group/listAll", Map.of());
    }

    public Map<String, Object> buildSaveBody(String name, String description, String method, String publicPath,
                                            String sql, List<Map<String, Object>> params,
                                            Long existingId, String contentType, Long datasourceId) {
        return buildSaveBody(name, description, method, publicPath, sql, params, existingId, contentType, datasourceId, "SQL");
    }

    public Map<String, Object> buildSaveBody(String name, String description, String method, String publicPath,
                                            String sqlOrScript, List<Map<String, Object>> params,
                                            Long existingId, String contentType, Long datasourceId, String engine) {
        LhProperties.Sqlrest cfg = lhProperties.getSqlrest();
        String eng = StrUtil.blankToDefault(engine, "SQL").trim().toUpperCase();
        if (!"GROOVY".equals(eng)) {
            eng = "SQL";
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (existingId != null) {
            body.put("id", existingId);
        }
        body.put("groupId", cfg.getDefaultGroupId());
        body.put("moduleId", cfg.getDefaultModuleId());
        body.put("datasourceId", datasourceId != null ? datasourceId : cfg.getDatasourceId());
        body.put("name", name);
        body.put("description", StrUtil.blankToDefault(description, name));
        body.put("method", StrUtil.blankToDefault(method, "GET").toUpperCase());
        body.put("contentType", StrUtil.blankToDefault(contentType, "application/x-www-form-urlencoded"));
        body.put("path", toSqlrestPath(publicPath));
        body.put("open", true);
        body.put("alarm", false);
        body.put("flowStatus", false);
        body.put("flowGrade", 1);
        body.put("flowCount", 5);
        body.put("cacheKeyType", "NONE");
        body.put("cacheExpireSeconds", 0);
        body.put("engine", eng);
        body.put("namingStrategy", "CAMEL_CASE");
        body.put("formatMap", List.of(Map.of(
                "key", "USE_SYSTEM_RESPONSE_FORMAT",
                "value", "true",
                "remark", "Response format")));
        String ctx = "GROOVY".equals(eng) ? StrUtil.nullToDefault(sqlOrScript, "") : toSqlrestSql(sqlOrScript);
        body.put("contextList", List.of(ctx));
        body.put("params", params != null ? params : List.of());
        body.put("outputs", List.of());
        return body;
    }

    /** @deprecated 使用带 datasourceId 的重载 */
    public Map<String, Object> buildSaveBody(String name, String description, String method, String publicPath,
                                            String sql, List<Map<String, Object>> params,
                                            Long existingId, String contentType) {
        return buildSaveBody(name, description, method, publicPath, sql, params, existingId, contentType, null, "SQL");
    }

    public List<Map<String, Object>> toSqlrestParams(List<Map<String, Object>> portalParams, String method) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (portalParams == null) {
            return out;
        }
        String location = "GET".equalsIgnoreCase(method) ? "REQUEST_FORM" : "REQUEST_BODY";
        for (Map<String, Object> p : portalParams) {
            if (p == null || StrUtil.isBlank(String.valueOf(p.getOrDefault("name", "")))) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", String.valueOf(p.get("name")).trim());
            item.put("type", mapParamType(p.get("type")));
            item.put("location", location);
            item.put("isArray", false);
            item.put("required", Boolean.TRUE.equals(p.get("required")) || "true".equalsIgnoreCase(String.valueOf(p.get("required"))));
            item.put("defaultValue", null);
            item.put("remark", p.get("desc") != null ? p.get("desc") : p.get("remark"));
            item.put("children", List.of());
            out.add(item);
        }
        return out;
    }

    public List<Map<String, Object>> toDebugParamValues(List<Map<String, Object>> portalParams) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (portalParams == null) {
            return out;
        }
        for (Map<String, Object> p : portalParams) {
            if (p == null || StrUtil.isBlank(String.valueOf(p.getOrDefault("name", "")))) {
                continue;
            }
            Object example = p.get("example");
            if (example == null || StrUtil.isBlank(String.valueOf(example))) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", String.valueOf(p.getOrDefault("id", itemNameId(p))));
            item.put("name", String.valueOf(p.get("name")).trim());
            item.put("type", mapParamType(p.get("type")));
            item.put("isArray", false);
            item.put("required", Boolean.TRUE.equals(p.get("required")));
            item.put("defaultValue", null);
            item.put("remark", p.get("desc"));
            item.put("value", String.valueOf(example));
            item.put("arrayValues", List.of());
            item.put("children", List.of());
            out.add(item);
        }
        return out;
    }

    private static String itemNameId(Map<String, Object> p) {
        return String.valueOf(p.get("name"));
    }

    private static String mapParamType(Object type) {
        String t = StrUtil.blankToDefault(type == null ? null : String.valueOf(type), "string").toLowerCase();
        return switch (t) {
            case "int", "integer", "long", "number" -> "LONG";
            case "double", "float", "decimal" -> "DOUBLE";
            case "bool", "boolean" -> "BOOLEAN";
            case "date" -> "DATE";
            case "datetime", "time", "timestamp" -> "TIMESTAMP";
            default -> "STRING";
        };
    }

    private Map<String, Object> getJson(String path) {
        try {
            String body = HttpRequest.get(url(path))
                    .header("Authorization", "Bearer " + accessToken())
                    .timeout(12000)
                    .execute()
                    .body();
            return wrapResp(body);
        } catch (Exception e) {
            return degraded(e.getMessage());
        }
    }

    private Map<String, Object> postJson(String path, Object payload) {
        try {
            String body = HttpRequest.post(url(path))
                    .header("Authorization", "Bearer " + accessToken())
                    .header("Content-Type", "application/json")
                    .body(JSONUtil.toJsonStr(payload))
                    .timeout(30000)
                    .execute()
                    .body();
            return wrapResp(body);
        } catch (Exception e) {
            return degraded(e.getMessage());
        }
    }

    private Map<String, Object> putJson(String path, Object payload) {
        try {
            String body = HttpRequest.put(url(path))
                    .header("Authorization", "Bearer " + accessToken())
                    .header("Content-Type", "application/json")
                    .body(JSONUtil.toJsonStr(payload == null ? Map.of() : payload))
                    .timeout(20000)
                    .execute()
                    .body();
            return wrapResp(body);
        } catch (Exception e) {
            return degraded(e.getMessage());
        }
    }

    private Map<String, Object> deleteJson(String path) {
        try {
            String body = HttpRequest.delete(url(path))
                    .header("Authorization", "Bearer " + accessToken())
                    .timeout(12000)
                    .execute()
                    .body();
            return wrapResp(body);
        } catch (Exception e) {
            return degraded(e.getMessage());
        }
    }

    private Map<String, Object> wrapResp(String body) {
        JSONObject json = JSONUtil.parseObj(body);
        Integer code = json.getInt("code");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", code != null && code == 0);
        m.put("code", code);
        m.put("message", json.getStr("message"));
        m.put("data", json.get("data"));
        if (json.get("pagination") != null) {
            m.put("pagination", json.get("pagination"));
        }
        if (code == null || code != 0) {
            m.put("degraded", true);
        }
        return m;
    }

    private Map<String, Object> degraded(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("degraded", true);
        m.put("message", message);
        return m;
    }

    private String accessToken() {
        CachedToken cached = tokenRef.get();
        long now = System.currentTimeMillis();
        if (cached != null && cached.expireAtMs > now + TOKEN_SKEW_MS) {
            return cached.token;
        }
        synchronized (this) {
            cached = tokenRef.get();
            if (cached != null && cached.expireAtMs > now + TOKEN_SKEW_MS) {
                return cached.token;
            }
            LhProperties.Sqlrest cfg = lhProperties.getSqlrest();
            String resp = HttpRequest.post(url("/user/login"))
                    .header("Content-Type", "application/json")
                    .body(JSONUtil.toJsonStr(Map.of(
                            "username", StrUtil.blankToDefault(cfg.getUsername(), "admin"),
                            "password", StrUtil.blankToDefault(cfg.getPassword(), "123456"))))
                    .timeout(8000)
                    .execute()
                    .body();
            JSONObject json = JSONUtil.parseObj(resp);
            if (json.getInt("code", -1) != 0) {
                throw new IllegalStateException("SQLREST login failed: " + json.getStr("message"));
            }
            JSONObject data = json.getJSONObject("data");
            String token = data.getStr("accessToken");
            long expireSec = data.getLong("expireSeconds", 7200L);
            tokenRef.set(new CachedToken(token, now + expireSec * 1000L));
            return token;
        }
    }

    private String url(String path) {
        return trim(lhProperties.getSqlrest().getManagerUrl()) + path;
    }

    private String trim(String u) {
        return u == null ? "" : (u.endsWith("/") ? u.substring(0, u.length() - 1) : u);
    }

    private record CachedToken(String token, long expireAtMs) {
    }
}
