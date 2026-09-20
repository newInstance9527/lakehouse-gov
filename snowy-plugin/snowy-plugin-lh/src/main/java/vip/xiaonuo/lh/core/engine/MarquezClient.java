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

import cn.hutool.core.codec.Base64;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.vault.LhComponentCredentialResolver;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Marquez / OpenLineage 作业血缘客户端（经 lh-ui-auth Basic）
 * <p>门户不把 Marquez Web 当主 UI；仅探活与作业命名空间摘要，图展示主读 OM。</p>
 *
 * @author lakehouse
 * @date 2026/9/19
 */
@Component
public class MarquezClient {

    @Resource
    private LhProperties lhProperties;
    @Resource
    private LhComponentCredentialResolver credentialResolver;

    public Map<String, Object> health() {
        try {
            String body = authGet("/api/v1/namespaces");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("component", "marquez");
            m.put("status", "UP");
            m.put("bodyPreview", StrUtil.maxLength(body, 120));
            return m;
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("component", "marquez");
            m.put("status", "DOWN");
            m.put("error", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
            return m;
        }
    }

    /**
     * 命名空间列表（soft-fail 包装）
     */
    public Map<String, Object> listNamespaces() {
        try {
            String body = authGet("/api/v1/namespaces");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("data", JSONUtil.parse(body));
            return m;
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("degraded", true);
            m.put("message", e.getMessage());
            return m;
        }
    }

    /**
     * 命名空间下作业列表摘要
     */
    public Map<String, Object> listJobs(String namespace, int limit) {
        if (StrUtil.isBlank(namespace)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("message", "namespace required");
            return m;
        }
        try {
            int lim = Math.max(1, Math.min(limit, 100));
            String body = authGet("/api/v1/namespaces/" + enc(namespace) + "/jobs?limit=" + lim);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("namespace", namespace);
            m.put("data", JSONUtil.parse(body));
            return m;
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("degraded", true);
            m.put("message", e.getMessage());
            return m;
        }
    }

    /**
     * 投递 OpenLineage 事件到 Marquez（soft-fail 包装）
     */
    public Map<String, Object> postLineageEvent(Map<String, Object> event) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            String body = authPost("/api/v1/lineage", JSONUtil.toJsonStr(event == null ? Map.of() : event));
            m.put("ok", true);
            m.put("resp", StrUtil.maxLength(body, 200));
            return m;
        } catch (Exception e) {
            m.put("ok", false);
            m.put("degraded", true);
            m.put("message", e.getMessage());
            return m;
        }
    }

    private String authGet(String path) {
        return authRequest("GET", path, null);
    }

    private String authPost(String path, String json) {
        return authRequest("POST", path, json);
    }

    private String authRequest(String method, String path, String json) {
        LhProperties.Marquez mz = lhProperties.getMarquez();
        String base = trim(mz.getUrl());
        Map<String, String> cred = credentialResolver.resolveUserPass(
                mz.getVaultPath(), mz.getUser(), mz.getPassword(), null);
        String user = StrUtil.blankToDefault(cred.get("username"), "admin");
        String pass = cred.get("password");
        if (StrUtil.isBlank(pass)) {
            throw new CommonException("Marquez 密码未配置（lh.marquez.password / Vault）");
        }
        String basic = Base64.encode(user + ":" + pass);
        HttpRequest req = "POST".equalsIgnoreCase(method)
                ? HttpRequest.post(base + path).body(json).header("Content-Type", "application/json")
                : HttpRequest.get(base + path);
        var resp = req.header("Authorization", "Basic " + basic)
                .timeout(15000)
                .execute();
        if (resp.getStatus() >= 400) {
            throw new CommonException("Marquez {} 失败 {}: {}", method, resp.getStatus(), resp.body());
        }
        return resp.body();
    }

    private static String trim(String url) {
        return url == null ? "" : (url.endsWith("/") ? url.substring(0, url.length() - 1) : url);
    }

    private static String enc(String s) {
        return s == null ? "" : s.replace(" ", "%20");
    }
}
