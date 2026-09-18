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

import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.vault.LhComponentCredentialResolver;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * APISIX Admin 客户端（X-API-KEY 来自 Vault）
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
     * 创建/更新路由
     */
    public Map<String, Object> upsertRoute(String routeId, String path, String method, String upstream) {
        Map<String, Object> route = new LinkedHashMap<>();
        route.put("uri", path);
        route.put("methods", java.util.List.of(method));
        route.put("upstream", Map.of("type", "roundrobin", "nodes", Map.of(upstream, 1)));
        route.put("plugins", Map.of("key-auth", Map.of(), "limit-req",
                Map.of("rate", 100, "burst", 50, "rejected_code", 429)));
        try {
            String url = trim(lhProperties.getApisix().getAdminUrl()) + "/apisix/admin/routes/" + routeId;
            String body = HttpRequest.put(url)
                    .header("X-API-KEY", credentialResolver.apisixApiKey())
                    .body(JSONUtil.toJsonStr(route))
                    .timeout(8000).execute().body();
            return Map.of("ok", true, "routeId", routeId, "resp", body);
        } catch (Exception e) {
            return Map.of("ok", true, "degraded", true, "routeId", routeId, "message", e.getMessage());
        }
    }

    /**
     * 删除路由
     */
    public Map<String, Object> deleteRoute(String routeId) {
        try {
            String url = trim(lhProperties.getApisix().getAdminUrl()) + "/apisix/admin/routes/" + routeId;
            HttpRequest.delete(url)
                    .header("X-API-KEY", credentialResolver.apisixApiKey())
                    .timeout(5000).execute();
            return Map.of("ok", true);
        } catch (Exception e) {
            return Map.of("ok", true, "degraded", true, "message", e.getMessage());
        }
    }

    private String trim(String url) {
        return url == null ? "" : (url.endsWith("/") ? url.substring(0, url.length() - 1) : url);
    }
}
