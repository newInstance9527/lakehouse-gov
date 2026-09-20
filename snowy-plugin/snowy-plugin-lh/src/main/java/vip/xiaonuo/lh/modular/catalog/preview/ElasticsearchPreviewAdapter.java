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

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Elasticsearch：HTTP {@code POST /{index}/_search} 样例文档（非 Grav / 非 Trino）。
 * <p>索引名保留原样（含日期后缀中的 {@code .}，禁止 shortName 截断）。</p>
 */
@Slf4j
@Component
@Order(25)
public class ElasticsearchPreviewAdapter implements GovAssetPreviewAdapter {

    private static final int VALUE_MAX = 800;

    @Resource
    private LhVaultClient vaultClient;

    @Override
    public int order() {
        return 25;
    }

    @Override
    public boolean supports(GovAssetPreviewContext ctx) {
        String t = PreviewAdapterSupport.dsType(ctx.getPrimaryDs());
        if (!("elasticsearch".equals(t) || "es".equals(t))) {
            return false;
        }
        return StrUtil.isNotBlank(ctx.getObjectName());
    }

    @Override
    public Map<String, Object> preview(GovAssetPreviewContext ctx) {
        LhDatasource ds = ctx.getPrimaryDs();
        // 索引名禁止按 . 截断（如 log_kong_request-2026.09）
        String index = resolveIndexName(ctx.getObjectName());
        int limit = Math.min(Math.max(ctx.getLimit(), 1), 50);
        Map<String, Object> r = ctx.newResult();
        r.put("qualifiedName", index);
        try {
            Map<String, Object> secret = vaultClient.readOrEmpty(ds.getVaultPath());
            String base = buildBaseUrl(ds, secret);
            if (StrUtil.isBlank(base)) {
                return PreviewAdapterSupport.emptyFail(r, "elasticsearch", "Elasticsearch 无 host，无法预览");
            }
            String user = first(secret, "user", "username");
            String password = first(secret, "password");
            boolean https = base.startsWith("https://");

            String url = base + "/" + encodeIndex(index) + "/_search";
            String body = JSONUtil.toJsonStr(Map.of(
                    "size", limit,
                    "query", Map.of("match_all", Map.of()),
                    "sort", List.of(Map.of("_doc", Map.of("order", "desc")))
            ));
            HttpRequest req = HttpRequest.post(url)
                    .timeout(12000)
                    .header("Content-Type", "application/json")
                    .body(body);
            if (StrUtil.isNotBlank(user)) {
                req.basicAuth(user, StrUtil.nullToEmpty(password));
            }
            if (https) {
                trustAll(req);
            }
            HttpResponse resp = req.execute();
            if (!resp.isOk()) {
                // 兼容无 sort 的旧集群：降级为简单 match_all
                if (resp.getStatus() == 400) {
                    body = JSONUtil.toJsonStr(Map.of("size", limit, "query", Map.of("match_all", Map.of())));
                    req = HttpRequest.post(url)
                            .timeout(12000)
                            .header("Content-Type", "application/json")
                            .body(body);
                    if (StrUtil.isNotBlank(user)) {
                        req.basicAuth(user, StrUtil.nullToEmpty(password));
                    }
                    if (https) {
                        trustAll(req);
                    }
                    resp = req.execute();
                }
            }
            if (!resp.isOk()) {
                return PreviewAdapterSupport.emptyFail(r, "elasticsearch",
                        "ES _search HTTP " + resp.getStatus() + "："
                                + StrUtil.maxLength(StrUtil.blankToDefault(resp.body(), ""), 200));
            }

            JSONObject root = JSONUtil.parseObj(resp.body());
            JSONObject hitsWrap = root.getJSONObject("hits");
            JSONArray hits = hitsWrap == null ? null : hitsWrap.getJSONArray("hits");
            List<Map<String, Object>> rows = new ArrayList<>();
            Set<String> colSet = new LinkedHashSet<>();
            colSet.add("_id");
            if (hits != null) {
                for (int i = 0; i < hits.size() && rows.size() < limit; i++) {
                    JSONObject hit = hits.getJSONObject(i);
                    if (hit == null) {
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("_id", hit.getStr("_id"));
                    Object source = hit.get("_source");
                    if (source instanceof JSONObject src) {
                        flattenSource(src, "", row, colSet, 0);
                    } else if (source != null) {
                        row.put("_source", truncate(String.valueOf(source)));
                        colSet.add("_source");
                    }
                    rows.add(row);
                }
            }
            List<String> columns = new ArrayList<>(colSet);
            // 保证列顺序稳定：_id 在前，其余按出现序
            r.put("ok", true);
            r.put("source", "elasticsearch");
            r.put("columns", columns);
            r.put("rows", normalizeRows(rows, columns));
            r.put("rowCount", rows.size());
            r.put("message", rows.isEmpty()
                    ? "已连接索引，暂无文档样例（空索引）"
                    : "Elasticsearch _search 样例（探查，非 Grav/Trino）");
            r.put("hint", "Gravitino 无 ES Catalog；目录预览走原生 HTTP API");
            return r;
        } catch (Exception e) {
            log.warn("ES preview fail index={}: {}", index, e.getMessage());
            Map<String, Object> fail = PreviewAdapterSupport.emptyFail(r, "elasticsearch",
                    "ES 预览失败: " + StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
            fail.put("degraded", true);
            return fail;
        }
    }

    /** 保留完整索引名；仅当形如 service.index 且左侧像 dsCode 时取右侧 */
    static String resolveIndexName(String objectName) {
        String raw = StrUtil.blankToDefault(objectName, "").trim();
        if (StrUtil.isBlank(raw)) {
            return raw;
        }
        // 路径分隔才截；点号是 ES 索引常见字符
        if (raw.contains("/")) {
            String[] parts = raw.split("/");
            return parts[parts.length - 1];
        }
        return raw;
    }

    private static String encodeIndex(String index) {
        // 简单编码：空格等；保留 - . _ 
        return index.replace(" ", "%20");
    }

    private static String buildBaseUrl(LhDatasource ds, Map<String, Object> secret) {
        String host = first(secret, "host", "endpoint");
        String port = first(secret, "port");
        if (StrUtil.isBlank(host) && StrUtil.isNotBlank(ds.getEndpointHost())) {
            host = ds.getEndpointHost();
        }
        if (StrUtil.isBlank(port) && StrUtil.isNotBlank(ds.getEndpointPort())) {
            port = ds.getEndpointPort();
        }
        if (StrUtil.isBlank(host)) {
            return null;
        }
        boolean https = useHttps(secret, host);
        host = host.replaceFirst("^https?://", "").replaceAll("/+$", "");
        if (host.contains(":") && !host.startsWith("[")) {
            int colon = host.lastIndexOf(':');
            if (colon > 0 && StrUtil.isBlank(port)) {
                port = host.substring(colon + 1);
                host = host.substring(0, colon);
            }
        }
        if (StrUtil.isBlank(port)) {
            port = "9200";
        }
        return (https ? "https://" : "http://") + host + ":" + port;
    }

    private static boolean useHttps(Map<String, Object> secret, String host) {
        if (StrUtil.startWithIgnoreCase(host, "https://")) {
            return true;
        }
        if (StrUtil.startWithIgnoreCase(host, "http://")) {
            return false;
        }
        String ssl = first(secret, "ssl", "secure", "https");
        if (StrUtil.isBlank(ssl)) {
            return false;
        }
        String s = ssl.toLowerCase(Locale.ROOT);
        return s.contains("开启") || "true".equals(s) || "yes".equals(s) || "1".equals(s) || "https".equals(s);
    }

    private static void flattenSource(JSONObject src, String prefix, Map<String, Object> row,
                                      Set<String> colSet, int depth) {
        if (src == null || depth > 2) {
            return;
        }
        for (String key : src.keySet()) {
            String col = StrUtil.isBlank(prefix) ? key : prefix + "." + key;
            Object val = src.get(key);
            if (val instanceof JSONObject nested && depth < 2) {
                flattenSource(nested, col, row, colSet, depth + 1);
            } else if (val instanceof JSONArray arr) {
                row.put(col, truncate(arr.toString()));
                colSet.add(col);
            } else {
                row.put(col, truncate(val == null ? null : String.valueOf(val)));
                colSet.add(col);
            }
        }
    }

    private static List<Map<String, Object>> normalizeRows(List<Map<String, Object>> rows, List<String> columns) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> n = new LinkedHashMap<>();
            for (String c : columns) {
                n.put(c, row.getOrDefault(c, null));
            }
            out.add(n);
        }
        return out;
    }

    private static String truncate(String v) {
        if (v == null) {
            return null;
        }
        if (v.length() > VALUE_MAX) {
            return v.substring(0, VALUE_MAX) + "…";
        }
        return v;
    }

    private static void trustAll(HttpRequest req) {
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
