package vip.xiaonuo.lh.modular.lifecycle.support;

import cn.hutool.core.util.StrUtil;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.engine.VictoriaMetricsClient;
import vip.xiaonuo.lh.core.vault.LhComponentCredentialResolver;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MinIO bucket inventory 校准：读预生成 CSV（key,size）合计，对照 VM 桶用量写 inventory 点。
 * <p>路径约定：{@code {inventoryReportBucket}/{prefix}/{srcBucket}/latest.csv}
 * （列至少含 size；可选 key）。无文件则跳过，禁止扫全桶。
 */
@Component
public class GovLcStorageInventoryCalibrator {

    private static final Logger log = LoggerFactory.getLogger(GovLcStorageInventoryCalibrator.class);

    @Resource
    private LhProperties lhProperties;
    @Resource
    private LhComponentCredentialResolver credentialResolver;
    @Resource
    private VictoriaMetricsClient victoriaMetricsClient;
    @Resource
    private GovLcBucketMetricsReader bucketMetricsReader;

    public Map<String, Object> calibrate() {
        Map<String, Object> out = new LinkedHashMap<>();
        LhProperties.Lifecycle lc = lhProperties.getLifecycle();
        if (lc == null || !lc.isInventoryCalibrateEnabled()) {
            out.put("skipped", true);
            out.put("message", "lh.lifecycle.inventory-calibrate-enabled=false");
            return out;
        }
        String reportBucket = StrUtil.trim(StrUtil.nullToEmpty(lc.getInventoryReportBucket()));
        String prefix = StrUtil.trim(StrUtil.nullToEmpty(lc.getInventoryReportPrefix()));
        if (StrUtil.isBlank(reportBucket)) {
            out.put("skipped", true);
            out.put("message", "未配置 lh.lifecycle.inventory-report-bucket");
            return out;
        }
        List<String> buckets = lc.getInventorySourceBuckets();
        if (buckets == null || buckets.isEmpty()) {
            buckets = new ArrayList<>();
            for (GovLcBucketMetricsReader.BucketSnapshot s : bucketMetricsReader.listBuckets()) {
                buckets.add(s.bucket());
            }
        }
        if (buckets.isEmpty()) {
            out.put("skipped", true);
            out.put("message", "无待校准桶");
            return out;
        }

        MinioClient client;
        try {
            client = buildClient();
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", "MinIO 不可达: " + StrUtil.maxLength(e.getMessage(), 120));
            return out;
        }

        List<Map<String, Object>> details = new ArrayList<>();
        StringBuilder prom = new StringBuilder();
        long nowMs = System.currentTimeMillis();
        int ok = 0;
        for (String bucket : buckets) {
            if (StrUtil.isBlank(bucket)) {
                continue;
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("bucket", bucket);
            String objectKey = joinPath(prefix, bucket, "latest.csv");
            one.put("object", reportBucket + "/" + objectKey);
            try (InputStream in = client.getObject(GetObjectArgs.builder()
                    .bucket(reportBucket)
                    .object(objectKey)
                    .build());
                 BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                long total = sumCsvSizes(br);
                one.put("inventoryBytes", total);
                Long used = null;
                for (GovLcBucketMetricsReader.BucketSnapshot s : bucketMetricsReader.listBuckets()) {
                    if (bucket.equals(s.bucket())) {
                        used = s.usedBytes();
                        break;
                    }
                }
                one.put("vmUsedBytes", used);
                if (used != null) {
                    one.put("deltaBytes", total - used);
                    one.put("deltaPct", used == 0 ? null : (total - used) * 100.0 / used);
                }
                prom.append("lh_bucket_storage_inventory_bytes{bucket=\"")
                        .append(esc(bucket)).append("\"} ").append(total).append(' ').append(nowMs).append('\n');
                one.put("ok", true);
                ok++;
            } catch (Exception e) {
                one.put("ok", false);
                one.put("message", StrUtil.maxLength(e.getMessage(), 160));
                log.debug("inventory calibrate skip bucket={}: {}", bucket, e.getMessage());
            }
            details.add(one);
        }
        Map<String, Object> vm = Map.of();
        if (prom.length() > 0 && victoriaMetricsClient.configured()) {
            vm = victoriaMetricsClient.importPrometheus(prom.toString());
        }
        out.put("ok", ok > 0);
        out.put("calibrated", ok);
        out.put("total", details.size());
        out.put("details", details);
        out.put("vm", vm);
        out.put("hint", "将 MinIO Inventory 导出为 latest.csv（含 size 列）放到约定路径后重跑");
        return out;
    }

    private MinioClient buildClient() {
        LhProperties.Minio m = lhProperties.getMinio();
        Map<String, String> cred = credentialResolver.minio();
        String endpoint = m == null ? "" : StrUtil.nullToEmpty(m.getUrl());
        if (StrUtil.isBlank(endpoint)) {
            throw new IllegalStateException("lh.minio.url 未配置");
        }
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(cred.get("accessKey"), cred.get("secretKey"))
                .build();
    }

    static long sumCsvSizes(BufferedReader br) throws Exception {
        String header = br.readLine();
        if (header == null) {
            return 0L;
        }
        String[] cols = header.split(",", -1);
        int sizeIdx = -1;
        for (int i = 0; i < cols.length; i++) {
            String c = cols[i].trim().replace("\"", "").toLowerCase(Locale.ROOT);
            if ("size".equals(c) || "size_bytes".equals(c) || "bytes".equals(c)) {
                sizeIdx = i;
                break;
            }
        }
        // 无表头：假定最后一列为 size
        boolean headerless = sizeIdx < 0;
        if (headerless) {
            sizeIdx = Math.max(0, cols.length - 1);
            // 第一行也是数据
            long sum = parseSize(cols[sizeIdx]);
            String line;
            while ((line = br.readLine()) != null) {
                if (StrUtil.isBlank(line)) {
                    continue;
                }
                String[] parts = line.split(",", -1);
                if (parts.length > sizeIdx) {
                    sum += parseSize(parts[sizeIdx]);
                }
            }
            return sum;
        }
        long sum = 0L;
        String line;
        while ((line = br.readLine()) != null) {
            if (StrUtil.isBlank(line)) {
                continue;
            }
            String[] parts = line.split(",", -1);
            if (parts.length > sizeIdx) {
                sum += parseSize(parts[sizeIdx]);
            }
        }
        return sum;
    }

    private static long parseSize(String raw) {
        if (StrUtil.isBlank(raw)) {
            return 0L;
        }
        String s = raw.trim().replace("\"", "");
        try {
            return (long) Double.parseDouble(s);
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String joinPath(String prefix, String bucket, String file) {
        String p = StrUtil.blankToDefault(prefix, "inventory");
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p + "/" + bucket + "/" + file;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
