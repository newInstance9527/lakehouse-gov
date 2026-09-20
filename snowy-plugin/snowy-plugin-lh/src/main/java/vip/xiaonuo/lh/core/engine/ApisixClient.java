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
import vip.xiaonuo.lh.core.vault.LhComponentCredentialResolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * APISIX Admin 客户端（X-API-KEY 来自 Vault / 配置）
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Component
public class ApisixClient {

    @Resource
    private LhProperties lhProperties;
    @Resource
    private LhComponentCredentialResolver credentialResolver;

    /**
     * 创建/更新路由；upstream 形如 host:port
     */
    public Map<String, Object> upsertRoute(String routeId, String path, String method, String upstream) {
        return upsertRoute(routeId, path, method, upstream, 100, 50);
    }

    public Map<String, Object> upsertRoute(String routeId, String path, String method, String upstream,
                                           int rate, int burst) {
        String uri = path.startsWith("/") ? path : "/" + path;
        Map<String, Object> plugins = new LinkedHashMap<>();
        Map<String, Object> limitReq = new LinkedHashMap<>();
        limitReq.put("rate", Math.max(1, rate));
        limitReq.put("burst", Math.max(rate, burst));
        limitReq.put("key", "remote_addr");
        limitReq.put("rejected_code", 429);
        plugins.put("limit-req", limitReq);
        if (lhProperties.getApisix().isKeyAuthEnabled()) {
            plugins.put("key-auth", Map.of());
        }

        Map<String, Object> route = new LinkedHashMap<>();
        route.put("uri", uri);
        route.put("methods", List.of(StrUtil.blankToDefault(method, "GET").toUpperCase()));
        route.put("upstream", Map.of(
                "type", "roundrobin",
                "scheme", "http",
                "nodes", Map.of(StrUtil.blankToDefault(upstream, "127.0.0.1:18091"), 1)));
        route.put("plugins", plugins);

        try {
            String url = trim(lhProperties.getApisix().getAdminUrl()) + "/apisix/admin/routes/" + routeId;
            String body = HttpRequest.put(url)
                    .header("X-API-KEY", credentialResolver.apisixApiKey())
                    .header("Content-Type", "application/json")
                    .body(JSONUtil.toJsonStr(route))
                    .timeout(8000).execute().body();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("routeId", routeId);
            m.put("resp", body);
            return m;
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("degraded", true);
            m.put("routeId", routeId);
            m.put("message", e.getMessage());
            return m;
        }
    }

    public Map<String, Object> deleteRoute(String routeId) {
        try {
            String url = trim(lhProperties.getApisix().getAdminUrl()) + "/apisix/admin/routes/" + routeId;
            HttpRequest.delete(url)
                    .header("X-API-KEY", credentialResolver.apisixApiKey())
                    .timeout(5000).execute();
            return Map.of("ok", true, "routeId", routeId);
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("degraded", true);
            m.put("message", e.getMessage());
            return m;
        }
    }

    public Map<String, Object> listRoutes() {
        try {
            String url = trim(lhProperties.getApisix().getAdminUrl()) + "/apisix/admin/routes";
            String body = HttpRequest.get(url)
                    .header("X-API-KEY", credentialResolver.apisixApiKey())
                    .timeout(8000).execute().body();
            JSONObject json = JSONUtil.parseObj(body);
            List<Map<String, Object>> list = new ArrayList<>();
            Object listNode = json.get("list");
            if (listNode instanceof JSONArray arr) {
                for (int i = 0; i < arr.size(); i++) {
                    JSONObject item = arr.getJSONObject(i);
                    JSONObject value = item.getJSONObject("value");
                    if (value == null) {
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", value.getStr("id", item.getStr("key")));
                    row.put("path", value.getStr("uri"));
                    row.put("methods", value.get("methods"));
                    row.put("status", value.getInt("status", 1) == 1 ? "ok" : "warn");
                    JSONObject upstream = value.getJSONObject("upstream");
                    if (upstream != null && upstream.get("nodes") != null) {
                        row.put("upstream", String.valueOf(upstream.get("nodes")));
                    }
                    JSONObject plugins = value.getJSONObject("plugins");
                    if (plugins != null && plugins.getJSONObject("limit-req") != null) {
                        JSONObject lr = plugins.getJSONObject("limit-req");
                        row.put("rate", lr.getInt("rate") + "/s");
                    }
                    row.put("auth", plugins != null && plugins.containsKey("key-auth") ? "Token" : "None");
                    row.put("breaker", "—");
                    row.put("meter", "✓");
                    list.add(row);
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("total", json.getInt("total", list.size()));
            m.put("list", list);
            return m;
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("degraded", true);
            m.put("message", e.getMessage());
            m.put("list", List.of());
            return m;
        }
    }

    private String trim(String url) {
        return url == null ? "" : (url.endsWith("/") ? url.substring(0, url.length() - 1) : url);
    }
}
