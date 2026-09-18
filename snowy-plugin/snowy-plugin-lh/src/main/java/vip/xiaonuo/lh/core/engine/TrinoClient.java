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
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.vault.LhComponentCredentialResolver;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;
import java.util.*;

/**
 * Trino 客户端（凭证来自 Vault）
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Component
public class TrinoClient {

    @Resource
    private LhProperties lhProperties;
    @Resource
    private LhComponentCredentialResolver credentialResolver;

    /**
     * 执行 SQL
     *
     * @param sql SQL
     * @return columns/rows
     */
    public Map<String, Object> execute(String sql) {
        String base = StrUtil.removeSuffix(lhProperties.getTrino().getUrl(), "/");
        Map<String, String> cred = credentialResolver.trino();
        String user = cred.get("username");
        String password = cred.get("password");
        try {
            HttpRequest req = HttpRequest.post(base + "/v1/statement")
                    .header("X-Trino-User", user)
                    .header("X-Trino-Catalog", "iceberg")
                    .header("X-Trino-Schema", "default")
                    .basicAuth(user, password)
                    .body(sql)
                    .timeout(30000);
            if (lhProperties.getTrino().isInsecureSsl()) {
                trustAll(req);
            }
            HttpResponse resp = req.execute();
            JSONObject body = JSONUtil.parseObj(resp.body());
            List<Map<String, Object>> rows = new ArrayList<>();
            List<String> columns = new ArrayList<>();
            String next = body.getStr("nextUri");
            collect(body, columns, rows);
            int guard = 0;
            while (StrUtil.isNotBlank(next) && guard++ < 50) {
                HttpRequest nreq = HttpRequest.get(next).timeout(30000).basicAuth(user, password);
                if (lhProperties.getTrino().isInsecureSsl()) {
                    trustAll(nreq);
                }
                JSONObject page = JSONUtil.parseObj(nreq.execute().body());
                collect(page, columns, rows);
                next = page.getStr("nextUri");
                if (page.containsKey("error")) {
                    throw new CommonException("Trino错误: {}", page.getByPath("error.message"));
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("columns", columns);
            result.put("rows", rows);
            result.put("rowCount", rows.size());
            return result;
        } catch (CommonException e) {
            throw e;
        } catch (Exception e) {
            Map<String, Object> degraded = new LinkedHashMap<>();
            degraded.put("columns", List.of("message"));
            degraded.put("rows", List.of(Map.of("message", "Trino暂不可达: " + e.getMessage())));
            degraded.put("rowCount", 1);
            degraded.put("degraded", true);
            return degraded;
        }
    }

    /**
     * Schema 树（Catalog 列表）
     *
     * @return 树节点
     */
    public List<Map<String, Object>> schemaTree() {
        Map<String, Object> res = execute("SHOW CATALOGS");
        List<Map<String, Object>> tree = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) res.getOrDefault("rows", List.of());
        for (Map<String, Object> row : rows) {
            Object cat = row.values().stream().findFirst().orElse(null);
            if (cat != null) {
                tree.add(Map.of("title", String.valueOf(cat), "key", String.valueOf(cat), "isLeaf", false));
            }
        }
        if (tree.isEmpty()) {
            tree.add(Map.of("title", "iceberg", "key", "iceberg", "children", List.of(
                    Map.of("title", "ods_trade", "key", "iceberg.ods_trade"),
                    Map.of("title", "dwd_trade", "key", "iceberg.dwd_trade"),
                    Map.of("title", "dws_trade", "key", "iceberg.dws_trade"),
                    Map.of("title", "ads_trade", "key", "iceberg.ads_trade")
            )));
        }
        return tree;
    }

    private void collect(JSONObject page, List<String> columns, List<Map<String, Object>> rows) {
        if (columns.isEmpty() && page.containsKey("columns")) {
            JSONArray cols = page.getJSONArray("columns");
            for (int i = 0; i < cols.size(); i++) {
                columns.add(cols.getJSONObject(i).getStr("name"));
            }
        }
        if (page.containsKey("data")) {
            JSONArray data = page.getJSONArray("data");
            for (int i = 0; i < data.size(); i++) {
                JSONArray line = data.getJSONArray(i);
                Map<String, Object> row = new LinkedHashMap<>();
                for (int c = 0; c < columns.size() && c < line.size(); c++) {
                    row.put(columns.get(c), line.get(c));
                }
                rows.add(row);
            }
        }
    }

    private void trustAll(HttpRequest req) {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {
                }

                public void checkServerTrusted(X509Certificate[] c, String a) {
                }

                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            req.setSSLSocketFactory(sc.getSocketFactory());
        } catch (Exception ignored) {
        }
    }
}
