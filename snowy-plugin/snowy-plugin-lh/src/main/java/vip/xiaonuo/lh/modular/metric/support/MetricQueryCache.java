package vip.xiaonuo.lh.modular.metric.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vip.xiaonuo.common.cache.CommonCacheOperator;
import vip.xiaonuo.lh.config.LhProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 指标 query 短缓存：{@code metric:q:{code}:{ver}:{paramHash}}，TTL 默认 60s；试跑不写。
 * Redis 不可用时 soft-fail（不阻断查询）。
 */
@Component
public class MetricQueryCache {

    private static final Logger log = LoggerFactory.getLogger(MetricQueryCache.class);
    private static final String KEY_PREFIX = "metric:q:";

    @Resource
    private CommonCacheOperator commonCacheOperator;
    @Resource
    private LhProperties lhProperties;

    public boolean enabled() {
        LhProperties.Metric m = lhProperties.getMetric();
        return m == null || m.isQueryCacheEnabled();
    }

    public int ttlSeconds() {
        LhProperties.Metric m = lhProperties.getMetric();
        int ttl = m == null ? 60 : m.getQueryCacheTtlSeconds();
        return Math.max(5, Math.min(ttl, 600));
    }

    public String cacheKey(String metricCode, String ver, Map<String, Object> params, int maxRows) {
        String code = StrUtil.blankToDefault(metricCode, "").trim().toUpperCase();
        String v = StrUtil.blankToDefault(ver, "").trim();
        String paramHash = paramHash(params, maxRows);
        return KEY_PREFIX + code + ":" + v + ":" + paramHash;
    }

    public static String paramHash(Map<String, Object> params, int maxRows) {
        Map<String, Object> norm = new LinkedHashMap<>();
        if (params != null) {
            params.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> norm.put(e.getKey(), e.getValue()));
        }
        norm.put("_maxRows", maxRows);
        return DigestUtil.sha256Hex(JSONUtil.toJsonStr(norm)).substring(0, 16);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> get(String key) {
        if (!enabled() || StrUtil.isBlank(key)) {
            return null;
        }
        try {
            Object raw = commonCacheOperator.get(key);
            if (raw instanceof Map<?, ?> m) {
                return (Map<String, Object>) m;
            }
            return null;
        } catch (Exception e) {
            log.debug("metric query cache get soft-fail: {}", e.getMessage());
            return null;
        }
    }

    public void put(String key, Map<String, Object> payload) {
        if (!enabled() || StrUtil.isBlank(key) || payload == null) {
            return;
        }
        try {
            Map<String, Object> copy = new LinkedHashMap<>(payload);
            copy.put("cached", true);
            commonCacheOperator.put(key, copy, ttlSeconds());
        } catch (Exception e) {
            log.debug("metric query cache put soft-fail: {}", e.getMessage());
        }
    }
}
