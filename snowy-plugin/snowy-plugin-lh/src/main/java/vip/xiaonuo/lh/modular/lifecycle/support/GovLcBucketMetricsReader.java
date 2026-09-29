package vip.xiaonuo.lh.modular.lifecycle.support;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.engine.VictoriaMetricsClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 读 VictoriaMetrics 桶水位（Categraf MinIO → VM），供 {@code /storage/buckets} 与
 * {@code /storage/trend?group=bucket}；不写、不重复采集。
 * <p>
 * 现网未配 {@code lh.lifecycle.vm-import-url} 时返回空；调用方空态，禁止种子回落。
 */
@Component
public class GovLcBucketMetricsReader {

    public record BucketSnapshot(
            String bucket,
            long usedBytes,
            Long capacityBytes,
            Long objectCount,
            Long scrapedAtSec,
            String source
    ) {
    }

    @Resource
    private VictoriaMetricsClient victoriaMetricsClient;
    @Resource
    private LhProperties lhProperties;

    public boolean available() {
        return victoriaMetricsClient.configured();
    }

    /**
     * Instant 拉桶列表；无点返回空。
     */
    public List<BucketSnapshot> listBuckets() {
        if (!available()) {
            return List.of();
        }
        Map<String, VictoriaMetricsClient.InstantSample> used =
                indexByBucket(victoriaMetricsClient.queryInstant(GovLcBucketMetricNames.usedBytesQuery()));
        if (used.isEmpty()) {
            return List.of();
        }
        Map<String, VictoriaMetricsClient.InstantSample> objects =
                indexByBucket(victoriaMetricsClient.queryInstant(GovLcBucketMetricNames.objectCountQuery()));
        Map<String, VictoriaMetricsClient.InstantSample> capacity =
                indexByBucket(victoriaMetricsClient.queryInstant(GovLcBucketMetricNames.capacityBytesQuery()));

        List<BucketSnapshot> out = new ArrayList<>();
        for (Map.Entry<String, VictoriaMetricsClient.InstantSample> e : used.entrySet()) {
            String bucket = e.getKey();
            VictoriaMetricsClient.InstantSample u = e.getValue();
            Long obj = objects.containsKey(bucket) ? (long) objects.get(bucket).value() : null;
            Long cap = resolveCapacity(bucket, capacity.get(bucket));
            String src = metricFamily(u.labels().get("__name__"));
            out.add(new BucketSnapshot(
                    bucket,
                    (long) u.value(),
                    cap,
                    obj,
                    u.timestampSec(),
                    src
            ));
        }
        return out;
    }

    /**
     * 分层 → 桶 capacity（days-to-full）；无映射/无点则回退软容量配置。
     */
    public long capacityForLayer(String layer) {
        String bucket = resolveBucketForLayer(layer);
        if (StrUtil.isNotBlank(bucket)) {
            Long fromVm = capacityForBucket(bucket);
            if (fromVm != null && fromVm > 0) {
                return fromVm;
            }
            Long fromCfg = capacityFromConfig(bucket);
            if (fromCfg != null && fromCfg > 0) {
                return fromCfg;
            }
        }
        return defaultCapacity();
    }

    public Long capacityForBucket(String bucket) {
        if (StrUtil.isBlank(bucket)) {
            return null;
        }
        Long cfg = capacityFromConfig(bucket);
        if (!available()) {
            return cfg;
        }
        String q = "(" + GovLcBucketMetricNames.capacityBytesQuery() + "){bucket=\""
                + GovLcStorageDaysToFullDeriver.escProm(bucket) + "\"}";
        List<VictoriaMetricsClient.InstantSample> samples = victoriaMetricsClient.queryInstant(q);
        if (!samples.isEmpty() && samples.get(0).value() > 0) {
            return (long) samples.get(0).value();
        }
        return cfg;
    }

    public String resolveBucketForLayer(String layer) {
        Map<String, String> map = layerBucketMap();
        if (map == null || map.isEmpty() || StrUtil.isBlank(layer)) {
            return defaultBucketForLayer(layer);
        }
        String hit = map.get(layer);
        if (StrUtil.isBlank(hit)) {
            hit = map.get(layer.toUpperCase(Locale.ROOT));
        }
        if (StrUtil.isBlank(hit)) {
            hit = map.get(layer.toLowerCase(Locale.ROOT));
        }
        return StrUtil.isNotBlank(hit) ? hit : defaultBucketForLayer(layer);
    }

    private Long resolveCapacity(String bucket, VictoriaMetricsClient.InstantSample sample) {
        if (sample != null && sample.value() > 0) {
            return (long) sample.value();
        }
        return capacityFromConfig(bucket);
    }

    private Long capacityFromConfig(String bucket) {
        Map<String, Long> cfg = bucketCapacityMap();
        if (cfg == null || cfg.isEmpty() || StrUtil.isBlank(bucket)) {
            return null;
        }
        Long v = cfg.get(bucket);
        if (v == null) {
            v = cfg.get(bucket.toLowerCase(Locale.ROOT));
        }
        return v != null && v > 0 ? v : null;
    }

    private long defaultCapacity() {
        if (lhProperties.getLifecycle() == null) {
            return 20L * 1024 * 1024 * 1024 * 1024;
        }
        return Math.max(0, lhProperties.getLifecycle().getForecastDefaultCapacityBytes());
    }

    private Map<String, Long> bucketCapacityMap() {
        if (lhProperties.getLifecycle() == null) {
            return Map.of();
        }
        return lhProperties.getLifecycle().getBucketCapacityBytes();
    }

    private Map<String, String> layerBucketMap() {
        if (lhProperties.getLifecycle() == null) {
            return Map.of();
        }
        return lhProperties.getLifecycle().getLayerBucketMap();
    }

    private static String defaultBucketForLayer(String layer) {
        if (StrUtil.isBlank(layer)) {
            return null;
        }
        return switch (layer.toUpperCase(Locale.ROOT)) {
            case "ODS" -> "iceberg-ods";
            case "DWD" -> "iceberg-dwd";
            case "DWS" -> "iceberg-dws";
            case "ADS", "DIM" -> "iceberg-dws";
            case "HOT", "CK" -> "clickhouse-hot";
            case "COLD", "ARCHIVE" -> "archive";
            default -> null;
        };
    }

    private static Map<String, VictoriaMetricsClient.InstantSample> indexByBucket(
            List<VictoriaMetricsClient.InstantSample> samples) {
        Map<String, VictoriaMetricsClient.InstantSample> out = new LinkedHashMap<>();
        if (samples == null) {
            return out;
        }
        for (VictoriaMetricsClient.InstantSample s : samples) {
            if (s == null || s.labels() == null) {
                continue;
            }
            String bucket = s.labels().get(GovLcBucketMetricNames.LABEL_BUCKET);
            if (StrUtil.isBlank(bucket)) {
                continue;
            }
            // 同桶多 server 时取较大值（避免重复叠加）
            VictoriaMetricsClient.InstantSample prev = out.get(bucket);
            if (prev == null || s.value() >= prev.value()) {
                out.put(bucket, s);
            }
        }
        return out;
    }

    private static String metricFamily(String name) {
        if (StrUtil.isBlank(name)) {
            return "vm";
        }
        if (name.startsWith("lh_bucket_storage_")) {
            return "lh_bucket_storage_*";
        }
        if (name.startsWith("minio_")) {
            return name;
        }
        return name;
    }
}
