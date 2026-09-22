package vip.xiaonuo.lh.core.engine;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * VictoriaMetrics 客户端：直写 {@code /api/v1/import/prometheus}，并可读 {@code /api/v1/query_range}；
 * 不经 Pushgateway。
 */
@Component
public class VictoriaMetricsClient {

    private static final Logger log = LoggerFactory.getLogger(VictoriaMetricsClient.class);

    /** query_range 日点：UTC epoch day + 数值。 */
    public record RangePoint(long epochDay, long value) {
    }

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

    /**
     * 拉取日粒度矩阵点，供 days-to-full 分段回归。未配置 URL 时返回空列表。
     *
     * @param promQl PromQL 表达式
     * @param startSec Unix 秒
     * @param endSec   Unix 秒
     * @param step     步进，如 {@code 1d}
     */
    public List<RangePoint> queryRangePoints(String promQl, long startSec, long endSec, String step) {
        List<RangePoint> points = new ArrayList<>();
        String url = importUrl();
        if (StrUtil.isBlank(url) || StrUtil.isBlank(promQl)) {
            return points;
        }
        String endpoint = trimSlash(url) + "/api/v1/query_range";
        int timeout = timeoutMs();
        try {
            HttpResponse resp = HttpRequest.get(endpoint)
                    .form("query", promQl)
                    .form("start", String.valueOf(startSec))
                    .form("end", String.valueOf(endSec))
                    .form("step", StrUtil.blankToDefault(step, "1d"))
                    .timeout(timeout)
                    .execute();
            if (resp.getStatus() < 200 || resp.getStatus() >= 300) {
                log.warn("VictoriaMetrics query_range HTTP {}: {}", resp.getStatus(),
                        StrUtil.maxLength(resp.body(), 200));
                return points;
            }
            JSONObject root = JSONUtil.parseObj(resp.body());
            if (!"success".equalsIgnoreCase(root.getStr("status"))) {
                return points;
            }
            JSONObject data = root.getJSONObject("data");
            if (data == null) {
                return points;
            }
            JSONArray result = data.getJSONArray("result");
            if (result == null || result.isEmpty()) {
                return points;
            }
            // 取第一条 series（fqtn 已在查询里收窄）
            JSONArray values = result.getJSONObject(0).getJSONArray("values");
            if (values == null) {
                return points;
            }
            for (int i = 0; i < values.size(); i++) {
                JSONArray pair = values.getJSONArray(i);
                if (pair == null || pair.size() < 2) {
                    continue;
                }
                long tsSec = pair.getLong(0);
                String raw = pair.getStr(1);
                if (StrUtil.isBlank(raw) || "NaN".equalsIgnoreCase(raw)) {
                    continue;
                }
                long bytes;
                try {
                    bytes = (long) Double.parseDouble(raw.trim());
                } catch (Exception e) {
                    continue;
                }
                long epochDay = tsSec / 86_400L;
                points.add(new RangePoint(epochDay, bytes));
            }
            return points;
        } catch (Exception e) {
            log.warn("VictoriaMetrics query_range error: {}", e.getMessage());
            return points;
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
