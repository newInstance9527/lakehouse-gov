package vip.xiaonuo.lh.modular.datasource.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import vip.xiaonuo.common.exception.CommonException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Elasticsearch 多节点：nodes / host 逗号分隔，依次探测首个可用节点。
 */
public final class LhEsConnSupport {

    private LhEsConnSupport() {
    }

    /**
     * 解析节点 base URL 列表（含 scheme）。
     */
    public static List<String> resolveBases(Map<String, Object> conn) {
        String nodes = first(conn, "nodes", "hosts", "endpoints");
        String host = first(conn, "host", "endpoint");
        String port = StrUtil.blankToDefault(first(conn, "port"), "9200");
        boolean defaultHttps = truthySsl(first(conn, "ssl", "secure", "https"));

        LinkedHashSet<String> raw = new LinkedHashSet<>();
        if (StrUtil.isNotBlank(nodes)) {
            for (String p : nodes.split("[,;\\s]+")) {
                if (StrUtil.isNotBlank(p)) {
                    raw.add(p.trim());
                }
            }
        }
        if (StrUtil.isNotBlank(host)) {
            if (host.contains(",") || host.contains(";")) {
                for (String p : host.split("[,;]+")) {
                    if (StrUtil.isNotBlank(p)) {
                        raw.add(p.trim());
                    }
                }
            } else {
                raw.add(host.trim());
            }
        }
        if (raw.isEmpty()) {
            throw new CommonException("Elasticsearch 需要 host 或 nodes（多节点逗号分隔）");
        }

        List<String> bases = new ArrayList<>();
        for (String entry : raw) {
            bases.add(toBaseUrl(entry, port, defaultHttps));
        }
        return bases;
    }

    public static String toBaseUrl(String entry, String defaultPort, boolean defaultHttps) {
        String e = entry.trim();
        boolean https = defaultHttps || StrUtil.startWithIgnoreCase(e, "https://");
        if (StrUtil.startWithIgnoreCase(e, "http://")) {
            https = false;
        }
        e = e.replaceFirst("^https?://", "").replaceAll("/+$", "");
        String host = e;
        String port = defaultPort;
        if (e.contains(":") && !e.startsWith("[")) {
            int colon = e.lastIndexOf(':');
            String maybePort = e.substring(colon + 1);
            if (StrUtil.isNumeric(maybePort)) {
                host = e.substring(0, colon);
                port = maybePort;
            }
        }
        return (https ? "https://" : "http://") + host + ":" + port;
    }

    /**
     * 对节点列表依次请求，返回首个成功结果；全部失败抛出汇总异常。
     */
    public static <T> T withFirstHealthy(Map<String, Object> conn, boolean insecureSsl,
                                         Function<String, T> callOnBase) {
        List<String> bases = resolveBases(conn);
        List<String> errors = new ArrayList<>();
        for (String base : bases) {
            try {
                return callOnBase.apply(base);
            } catch (CommonException e) {
                errors.add(base + " → " + e.getMessage());
            } catch (Exception e) {
                errors.add(base + " → " + e.getMessage());
            }
        }
        throw new CommonException("Elasticsearch 多节点均不可达：{}", String.join(" | ", errors));
    }

    public static HttpResponse get(String base, String path, Map<String, Object> conn, boolean insecureSsl) {
        HttpRequest req = HttpRequest.get(base + path).timeout(12000);
        applyAuth(req, conn);
        if (base.startsWith("https://") && insecureSsl) {
            trustAll(req);
        }
        HttpResponse resp = req.execute();
        if (!resp.isOk()) {
            throw new CommonException("HTTP {}：{}", resp.getStatus(), StrUtil.maxLength(resp.body(), 160));
        }
        return resp;
    }

    public static HttpResponse post(String base, String path, String jsonBody,
                                    Map<String, Object> conn, boolean insecureSsl) {
        HttpRequest req = HttpRequest.post(base + path)
                .timeout(20000)
                .header("Content-Type", "application/json")
                .body(StrUtil.nullToEmpty(jsonBody));
        applyAuth(req, conn);
        if (base.startsWith("https://") && insecureSsl) {
            trustAll(req);
        }
        HttpResponse resp = req.execute();
        if (!resp.isOk()) {
            throw new CommonException("HTTP {}：{}", resp.getStatus(), StrUtil.maxLength(resp.body(), 160));
        }
        return resp;
    }

    public static void applyAuth(HttpRequest req, Map<String, Object> conn) {
        String apiKey = first(conn, "apiKey", "apiKeyId", "api_key");
        String user = first(conn, "user", "username");
        String password = first(conn, "password");
        if (StrUtil.isNotBlank(apiKey) && !apiKey.contains("建议")) {
            String token = apiKey.contains(":")
                    ? Base64.getEncoder().encodeToString(apiKey.getBytes(StandardCharsets.UTF_8))
                    : apiKey.trim();
            req.header("Authorization", "ApiKey " + token);
        } else if (StrUtil.isNotBlank(user)) {
            req.basicAuth(user, StrUtil.nullToEmpty(password));
        }
    }

    public static boolean truthySsl(String ssl) {
        if (StrUtil.isBlank(ssl)) {
            return false;
        }
        String s = ssl.toLowerCase(Locale.ROOT);
        return s.contains("开启") || s.contains("启用") || "true".equals(s) || "yes".equals(s)
                || "1".equals(s) || "https".equals(s) || "ssl".equals(s);
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
        if (m == null) {
            return "";
        }
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null && StrUtil.isNotBlank(String.valueOf(v))) {
                return String.valueOf(v).trim();
            }
        }
        return "";
    }
}
