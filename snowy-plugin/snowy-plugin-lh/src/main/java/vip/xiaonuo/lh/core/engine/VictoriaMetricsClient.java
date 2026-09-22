package vip.xiaonuo.lh.core.engine;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * VictoriaMetrics 写入客户端：直写 {@code /api/v1/import/prometheus}，不经 Pushgateway。
 */
@Component
public class VictoriaMetricsClient {

    private static final Logger log = LoggerFactory.getLogger(VictoriaMetricsClient.class);

    @Resource
    private LhProperties lhProperties;

    public boolean configured() {
        return StrUtil.isNotBlank(importUrl());
    }

    /**
     * @param body Prometheus 文本（可含时间戳行）
     * @return status / degraded / message / bytes
     */
    public Map<String, Object> importPrometheus(String body) {
        Map<String, Object> out = new LinkedHashMap<>();
        String url = importUrl();
        if (StrUtil.isBlank(url)) {
            out.put("ok", false);
            out.put("skipped", true);
            out.put("degraded", true);
            out.put("message", "lh.lifecycle.vm-import-url 未配置，跳过 VM 写入");
            return out;
        }
        if (StrUtil.isBlank(body)) {
            out.put("ok", true);
            out.put("skipped", true);
            out.put("message", "无样本可写");
            out.put("bytes", 0);
            return out;
        }
        String endpoint = trimSlash(url) + "/api/v1/import/prometheus";
        int timeout = timeoutMs();
        try {
            HttpResponse resp = HttpRequest.post(endpoint)
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .timeout(timeout)
                    .body(body)
                    .execute();
            int code = resp.getStatus();
            out.put("httpStatus", code);
            out.put("bytes", body.length());
            out.put("endpoint", endpoint);
            if (code >= 200 && code < 300) {
                out.put("ok", true);
                out.put("message", "imported");
                return out;
            }
            out.put("ok", false);
            out.put("degraded", true);
            out.put("message", "VM import HTTP " + code + ": " + StrUtil.maxLength(resp.body(), 200));
            log.warn("VictoriaMetrics import failed status={} body={}", code, StrUtil.maxLength(resp.body(), 200));
            return out;
        } catch (Exception e) {
            out.put("ok", false);
            out.put("degraded", true);
            out.put("endpoint", endpoint);
            out.put("message", StrUtil.maxLength(e.getMessage(), 300));
            log.warn("VictoriaMetrics import error: {}", e.getMessage());
            return out;
        }
    }

    private String importUrl() {
        if (lhProperties.getLifecycle() == null) {
            return "";
        }
        return StrUtil.trim(StrUtil.nullToEmpty(lhProperties.getLifecycle().getVmImportUrl()));
    }

    private int timeoutMs() {
        if (lhProperties.getLifecycle() == null) {
            return 15_000;
        }
        return Math.max(3_000, lhProperties.getLifecycle().getVmImportTimeoutMs());
    }

    private static String trimSlash(String url) {
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }
}
