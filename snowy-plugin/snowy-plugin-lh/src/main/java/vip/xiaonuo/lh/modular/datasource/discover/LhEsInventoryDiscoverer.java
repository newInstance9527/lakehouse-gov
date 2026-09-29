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
package vip.xiaonuo.lh.modular.datasource.discover;

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
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Elasticsearch 清单发现：HTTP GET /_cat/indices?format=json
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Slf4j
@Component
@Order(10)
public class LhEsInventoryDiscoverer implements LhInventoryDiscoverer {

    @Resource
    private LhVaultClient vaultClient;
    @Resource
    private LhProperties lhProperties;

    @Override
    public boolean supports(String typeCode) {
        return "elasticsearch".equalsIgnoreCase(StrUtil.blankToDefault(typeCode, ""));
    }

    @Override
    public String objectKind(String typeCode) {
        return LhInventoryObjectKinds.INDEX;
    }

    @Override
    public LhInventoryDiscoverResult discover(LhDatasource ds) {
        Map<String, Object> secret = vaultClient.readOrEmpty(ds.getVaultPath());
        String host = first(secret, "host", "endpoint");
        String port = first(secret, "port");
        if (StrUtil.isBlank(host) && StrUtil.isNotBlank(ds.getEndpointHost())) {
            host = ds.getEndpointHost();
        }
        if (StrUtil.isBlank(port) && StrUtil.isNotBlank(ds.getEndpointPort())) {
            port = ds.getEndpointPort();
        }
        if (StrUtil.isBlank(host)) {
            throw new CommonException("Elasticsearch 清单同步需要 host（Vault / 端点）");
        }
        if (StrUtil.isBlank(port)) {
            port = "9200";
        }

        boolean https = useHttps(secret, host);
        host = host.replaceFirst("^https?://", "").replaceAll("/+$", "");
        if (host.contains(":") && !host.startsWith("[")) {
            // host 已带端口则不再拼接
            int colon = host.lastIndexOf(':');
            if (colon > 0 && StrUtil.isBlank(first(secret, "port")) && StrUtil.isBlank(ds.getEndpointPort())) {
                port = host.substring(colon + 1);
                host = host.substring(0, colon);
            }
        }
        String base = (https ? "https://" : "http://") + host + ":" + port;
        String url = base + "/_cat/indices?format=json&h=index,docs.count,store.size";

        String user = first(secret, "user", "username");
        String password = first(secret, "password");
        boolean skipSystem = skipSystemIndices(secret);

        try {
            HttpRequest req = HttpRequest.get(url).timeout(12000);
            if (StrUtil.isNotBlank(user)) {
                req.basicAuth(user, StrUtil.nullToEmpty(password));
            }
            if (https && lhProperties.getHttp().isInsecureSsl()) {
                trustAll(req);
            }
            HttpResponse resp = req.execute();
            if (!resp.isOk()) {
                throw new CommonException("Elasticsearch /_cat/indices HTTP {}：{}",
                        resp.getStatus(), StrUtil.maxLength(resp.body(), 200));
            }
            JSONArray arr = JSONUtil.parseArray(resp.body());
            List<LhRemoteInventoryItem> items = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                JSONObject row = arr.getJSONObject(i);
                if (row == null) {
                    continue;
                }
                String index = StrUtil.trim(row.getStr("index"));
                if (StrUtil.isBlank(index)) {
                    continue;
                }
                if (skipSystem && index.startsWith(".")) {
                    continue;
                }
                LhRemoteInventoryItem m = new LhRemoteInventoryItem();
                m.name = index;
                m.engine = "elasticsearch";
                m.encoding = "UTF-8";
                String docs = row.getStr("docs.count");
                String store = row.getStr("store.size");
                m.comment = "docs=" + StrUtil.blankToDefault(docs, "?")
                        + ", size=" + StrUtil.blankToDefault(store, "?");
                if (StrUtil.isNotBlank(docs) && StrUtil.isNumeric(docs)) {
                    m.rowCount = Long.parseLong(docs);
                }
                items.add(m);
            }
            return LhInventoryDiscoverResult.remote("es_cat", LhInventoryObjectKinds.INDEX, items);
        } catch (CommonException e) {
            throw e;
        } catch (Exception e) {
            throw new CommonException("Elasticsearch 清单同步失败: {}", e.getMessage());
        }
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

    /** 默认跳过 `.` 开头系统索引；Vault skipSystemIndices=false / includeSystem=true 可关闭 */
    private static boolean skipSystemIndices(Map<String, Object> secret) {
        String skip = first(secret, "skipSystemIndices", "skipSystem");
        if (StrUtil.isNotBlank(skip)) {
            String s = skip.toLowerCase(Locale.ROOT);
            return !("false".equals(s) || "0".equals(s) || "no".equals(s));
        }
        String include = first(secret, "includeSystem", "includeSystemIndices");
        if (StrUtil.isNotBlank(include)) {
            String s = include.toLowerCase(Locale.ROOT);
            return !("true".equals(s) || "1".equals(s) || "yes".equals(s) || s.contains("开启"));
        }
        return true;
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
