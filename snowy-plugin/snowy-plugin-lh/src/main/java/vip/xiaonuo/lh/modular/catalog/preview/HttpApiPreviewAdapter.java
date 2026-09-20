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
package vip.xiaonuo.lh.modular.catalog.preview;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP API 源：GET 样例响应（截断），非 Grav。
 */
@Slf4j
@Component
@Order(50)
public class HttpApiPreviewAdapter implements GovAssetPreviewAdapter {

    private static final int BODY_MAX = 4000;

    @Resource
    private LhVaultClient vaultClient;

    @Override
    public int order() {
        return 50;
    }

    @Override
    public boolean supports(GovAssetPreviewContext ctx) {
        String t = PreviewAdapterSupport.dsType(ctx.getPrimaryDs());
        return "http_api".equals(t) || "api".equals(t);
    }

    @Override
    public Map<String, Object> preview(GovAssetPreviewContext ctx) {
        LhDatasource ds = ctx.getPrimaryDs();
        Map<String, Object> r = ctx.newResult();
        String objectName = StrUtil.blankToDefault(ctx.getObjectName(), "");
        r.put("qualifiedName", objectName);
        try {
            Map<String, Object> secret = vaultClient.readOrEmpty(ds.getVaultPath());
            String url = first(secret, "url", "baseUrl", "endpoint", "host");
            if (StrUtil.isBlank(url) && StrUtil.isNotBlank(ds.getEndpointHost())) {
                url = ds.getEndpointHost();
            }
            if (StrUtil.isBlank(url)) {
                return PreviewAdapterSupport.emptyFail(r, "http_api", "HTTP API 无 url，无法预览");
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://" + url;
            }
            // 对象名若为相对 path 则拼接
            if (StrUtil.isNotBlank(objectName) && !objectName.startsWith("http")
                    && !objectName.equals(url)) {
                if (objectName.startsWith("/")) {
                    url = url.replaceAll("/+$", "") + objectName;
                } else if (!url.contains(objectName)) {
                    url = url.replaceAll("/+$", "") + "/" + objectName;
                }
            }
            String user = first(secret, "user", "username");
            String password = first(secret, "password");
            String token = first(secret, "token", "bearer", "apiKey", "api_key");

            HttpRequest req = HttpRequest.get(url).timeout(10000);
            if (StrUtil.isNotBlank(token)) {
                String t = token.startsWith("Bearer ") ? token : "Bearer " + token;
                req.header("Authorization", t);
            } else if (StrUtil.isNotBlank(user)) {
                req.basicAuth(user, StrUtil.nullToEmpty(password));
            }
            HttpResponse resp = req.execute();
            String body = StrUtil.blankToDefault(resp.body(), "");
            if (body.length() > BODY_MAX) {
                body = body.substring(0, BODY_MAX) + "…";
            }

            List<String> columns;
            List<Map<String, Object>> rows = new ArrayList<>();
            if (JSONUtil.isTypeJSONArray(body)) {
                JSONArray arr = JSONUtil.parseArray(body);
                columns = List.of("index", "item");
                int n = Math.min(arr.size(), ctx.getLimit());
                for (int i = 0; i < n; i++) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("index", i);
                    row.put("item", truncate(String.valueOf(arr.get(i)), 800));
                    rows.add(row);
                }
            } else if (JSONUtil.isTypeJSONObject(body)) {
                JSONObject obj = JSONUtil.parseObj(body);
                columns = new ArrayList<>(obj.keySet());
                if (columns.isEmpty()) {
                    columns = List.of("body");
                }
                Map<String, Object> row = new LinkedHashMap<>();
                for (String c : columns) {
                    Object v = obj.get(c);
                    row.put(c, truncate(v == null ? null : String.valueOf(v), 800));
                }
                rows.add(row);
            } else {
                columns = List.of("status", "body");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("status", resp.getStatus());
                row.put("body", body);
                rows.add(row);
            }

            r.put("ok", resp.isOk());
            r.put("source", "http_api");
            r.put("columns", columns);
            r.put("rows", rows);
            r.put("rowCount", rows.size());
            r.put("message", resp.isOk()
                    ? "HTTP API GET 样例（探查，非 Grav/Trino）"
                    : "HTTP " + resp.getStatus() + " · 已返回响应片段");
            if (!resp.isOk()) {
                r.put("degraded", true);
            }
            return r;
        } catch (Exception e) {
            log.warn("HTTP API preview fail: {}", e.getMessage());
            Map<String, Object> fail = PreviewAdapterSupport.emptyFail(r, "http_api",
                    "HTTP API 预览失败: " + StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
            fail.put("degraded", true);
            return fail;
        }
    }

    private static String truncate(String v, int max) {
        if (v == null) {
            return null;
        }
        return v.length() > max ? v.substring(0, max) + "…" : v;
    }

    private static String first(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null && StrUtil.isNotBlank(String.valueOf(v))) {
                return String.valueOf(v).trim();
            }
        }
        return "";
    }
}
