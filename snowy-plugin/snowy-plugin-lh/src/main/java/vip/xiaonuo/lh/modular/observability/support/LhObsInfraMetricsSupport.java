package vip.xiaonuo.lh.modular.observability.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.engine.VictoriaMetricsClient;
import vip.xiaonuo.lh.modular.lifecycle.support.GovLcBucketMetricsReader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 基础设施监控：从 VictoriaMetrics（node_exporter / 存储指标）与组件 HTTP 探针读真值；
 * 无采集时返回空，禁止演示数据。
 */
@Component
public class LhObsInfraMetricsSupport {

    @Resource
    private VictoriaMetricsClient victoriaMetricsClient;
    @Resource
    private GovLcBucketMetricsReader bucketMetricsReader;
    @Resource
    private LhProperties lhProperties;

    public boolean vmConfigured() {
        return victoriaMetricsClient.configured();
    }

    /**
     * L0 节点：兼容 node_exporter / Categraf node 指标。无点返回空。
     */
    public List<Map<String, Object>> loadNodes() {
        if (!vmConfigured()) {
            return List.of();
        }
        Map<String, Double> cpu = indexByInstance(
                victoriaMetricsClient.queryInstant(
                        "100 - (avg by (instance) (rate(node_cpu_seconds_total{mode=\"idle\"}[5m])) * 100)"));
        Map<String, Double> mem = indexByInstance(
                victoriaMetricsClient.queryInstant(
                        "(1 - (node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes)) * 100"));
        Map<String, Double> disk = indexByInstance(
                victoriaMetricsClient.queryInstant(
                        "(1 - (node_filesystem_avail_bytes{fstype!~\"tmpfs|overlay|squashfs\",mountpoint=\"/\"}"
                                + " / node_filesystem_size_bytes{fstype!~\"tmpfs|overlay|squashfs\",mountpoint=\"/\"})) * 100"));
        Map<String, Double> netRx = indexByInstance(
                victoriaMetricsClient.queryInstant(
                        "sum by (instance) (rate(node_network_receive_bytes_total{device!~\"lo|veth.*|docker.*|br-.*\"}[5m]))"));
        Map<String, Double> netTx = indexByInstance(
                victoriaMetricsClient.queryInstant(
                        "sum by (instance) (rate(node_network_transmit_bytes_total{device!~\"lo|veth.*|docker.*|br-.*\"}[5m]))"));

        if (cpu.isEmpty() && mem.isEmpty() && disk.isEmpty()) {
            return List.of();
        }

        List<String> instances = new ArrayList<>();
        for (String k : cpu.keySet()) {
            if (!instances.contains(k)) {
                instances.add(k);
            }
        }
        for (String k : mem.keySet()) {
            if (!instances.contains(k)) {
                instances.add(k);
            }
        }
        for (String k : disk.keySet()) {
            if (!instances.contains(k)) {
                instances.add(k);
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (String inst : instances) {
            int cpuPct = pct(cpu.get(inst));
            int memPct = pct(mem.get(inst));
            int diskPct = pct(disk.get(inst));
            String st = "ok";
            if (cpuPct >= 90 || memPct >= 90 || diskPct >= 90) {
                st = "warn";
            }
            if (cpuPct <= 0 && memPct <= 0 && diskPct <= 0) {
                st = "down";
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("node", hostOf(inst));
            row.put("role", "node");
            row.put("comps", "host");
            row.put("cpu", cpuPct);
            row.put("mem", memPct);
            row.put("disk", diskPct);
            row.put("net", formatNet(netRx.get(inst), netTx.get(inst)));
            row.put("st", st);
            row.put("instance", inst);
            rows.add(row);
        }
        return rows;
    }

    /**
     * L3 进程：优先 VM {@code up{job=...}}；否则对已配置组件 URL 做短超时 HTTP 探针。
     */
    public List<Map<String, Object>> loadProcs() {
        List<Map<String, Object>> fromVm = loadProcsFromVm();
        if (!fromVm.isEmpty()) {
            return fromVm;
        }
        return loadProcsFromHttpProbe();
    }

    public List<Map<String, Object>> loadAlertsFromVm() {
        if (!vmConfigured()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        // 与 nightingale-storage 规则对齐的瞬时评估（门户展示；夜莺仍负责通知）
        addAlertSamples(out,
                "(lh_bucket_storage_used_bytes / on(bucket) lh_bucket_storage_capacity_bytes) >= 0.8",
                "P1", "容量", "桶水位 ≥ 80%", "扩容");
        addAlertSamples(out,
                "lh_table_storage_days_to_full{quantile=\"p95\"} < 45 and lh_table_storage_days_to_full{quantile=\"p95\"} > 0",
                "P0", "容量", "表 days-to-full(p95) < 45d", "存储趋势→");
        addAlertSamples(out,
                "lh_table_storage_small_file_ratio > 0.30",
                "P1", "生命周期", "小文件占比 > 30%", "生命周期→");
        addAlertSamples(out,
                "(time() - timestamp(lh_table_storage_bytes{kind=\"total\"})) > 86400",
                "P0", "采集", "表存储画像断流 >24h", "排查日批");
        // 节点磁盘
        addAlertSamples(out,
                "(1 - (node_filesystem_avail_bytes{fstype!~\"tmpfs|overlay|squashfs\",mountpoint=\"/\"}"
                        + " / node_filesystem_size_bytes{fstype!~\"tmpfs|overlay|squashfs\",mountpoint=\"/\"})) > 0.85",
                "P1", "磁盘", "节点根盘使用率 > 85%", "扩容");
        addAlertSamples(out,
                "100 - (avg by (instance) (rate(node_cpu_seconds_total{mode=\"idle\"}[5m])) * 100) > 90",
                "P1", "CPU", "节点 CPU > 90%", "链路影响→");
        return out;
    }

    public List<Map<String, Object>> capacityTipsFromBuckets() {
        List<Map<String, Object>> tips = new ArrayList<>();
        if (!bucketMetricsReader.available()) {
            return tips;
        }
        List<GovLcBucketMetricsReader.BucketSnapshot> buckets = bucketMetricsReader.listBuckets();
        for (GovLcBucketMetricsReader.BucketSnapshot b : buckets) {
            if (b.capacityBytes() == null || b.capacityBytes() <= 0) {
                continue;
            }
            double ratio = (double) b.usedBytes() / (double) b.capacityBytes();
            if (ratio >= 0.8) {
                Map<String, Object> tip = new LinkedHashMap<>();
                tip.put("icon", "⚠️");
                tip.put("bold", b.bucket() + " ");
                tip.put("text", String.format(Locale.ROOT, "用量 %.0f%% · 深链存储趋势扩容/治理", ratio * 100));
                tip.put("bucket", b.bucket());
                tips.add(tip);
            }
        }
        if (tips.isEmpty() && !buckets.isEmpty()) {
            Map<String, Object> tip = new LinkedHashMap<>();
            tip.put("icon", "✅");
            tip.put("bold", null);
            tip.put("text", "桶水位均低于 80%（来源 VM / Categraf）");
            tips.add(tip);
        }
        return tips;
    }

    /**
     * L1 容器：cadvisor 指标；无点返回空。
     */
    public List<Map<String, Object>> loadContainers() {
        if (!vmConfigured()) {
            return List.of();
        }
        List<VictoriaMetricsClient.InstantSample> cpu = victoriaMetricsClient.queryInstant(
                "topk(20, sum by (name, instance) (rate(container_cpu_usage_seconds_total{name!=\"\"}[5m])) * 100)");
        if (cpu.isEmpty()) {
            cpu = victoriaMetricsClient.queryInstant(
                    "topk(20, sum by (container, instance) (rate(container_cpu_usage_seconds_total{container!=\"\",container!=\"POD\"}[5m])) * 100)");
        }
        Map<String, Double> mem = indexByKey(
                victoriaMetricsClient.queryInstant(
                        "sum by (name, instance) (container_memory_working_set_bytes{name!=\"\"})"),
                "name", "container");
        if (cpu.isEmpty() && mem.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (VictoriaMetricsClient.InstantSample s : cpu) {
            String name = firstLabel(s, "name", "container");
            String inst = firstLabel(s, "instance");
            if (StrUtil.isBlank(name)) {
                continue;
            }
            String key = name + "@" + StrUtil.blankToDefault(inst, "");
            int cpuPct = pct(s.value());
            Double memBytes = mem.get(key);
            if (memBytes == null) {
                memBytes = mem.get(name);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("instance", StrUtil.blankToDefault(inst, "-"));
            row.put("cpu", cpuPct);
            row.put("mem", memBytes == null ? "—" : humanBytes(memBytes));
            row.put("st", cpuPct >= 80 ? "warn" : "ok");
            rows.add(row);
            if (rows.size() >= 20) {
                break;
            }
        }
        return rows;
    }

    /**
     * L2 集群对象：kube-state-metrics；无点返回空。
     */
    public List<Map<String, Object>> loadCluster() {
        if (!vmConfigured()) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        List<VictoriaMetricsClient.InstantSample> nodesReady = victoriaMetricsClient.queryInstant(
                "kube_node_status_condition{condition=\"Ready\",status=\"true\"}");
        for (VictoriaMetricsClient.InstantSample s : nodesReady) {
            String node = firstLabel(s, "node");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", "Node");
            row.put("name", StrUtil.blankToDefault(node, "-"));
            row.put("ready", s.value() >= 1 ? "Ready" : "NotReady");
            row.put("st", s.value() >= 1 ? "ok" : "down");
            rows.add(row);
        }
        List<VictoriaMetricsClient.InstantSample> deploys = victoriaMetricsClient.queryInstant(
                "kube_deployment_status_replicas_available");
        Map<String, Double> desired = indexByKey(
                victoriaMetricsClient.queryInstant("kube_deployment_spec_replicas"),
                "deployment", "namespace");
        for (VictoriaMetricsClient.InstantSample s : deploys) {
            String dep = firstLabel(s, "deployment");
            String ns = firstLabel(s, "namespace");
            if (StrUtil.isBlank(dep)) {
                continue;
            }
            String key = dep + "@" + StrUtil.blankToDefault(ns, "");
            double avail = s.value();
            Double want = desired.get(key);
            if (want == null) {
                want = desired.get(dep);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", "Deployment");
            row.put("name", StrUtil.isBlank(ns) ? dep : ns + "/" + dep);
            row.put("ready", want == null
                    ? String.format(Locale.ROOT, "%.0f", avail)
                    : String.format(Locale.ROOT, "%.0f/%.0f", avail, want));
            boolean ok = want == null || avail >= want;
            row.put("st", ok ? "ok" : "warn");
            rows.add(row);
            if (rows.size() >= 40) {
                break;
            }
        }
        return rows;
    }

    private List<Map<String, Object>> loadProcsFromVm() {
        if (!vmConfigured()) {
            return List.of();
        }
        List<VictoriaMetricsClient.InstantSample> ups =
                victoriaMetricsClient.queryInstant("up{job=~\"trino|flink|dolphinscheduler|minio|gravitino|clickhouse|openmetadata|ds|jm|tm\"}");
        if (ups.isEmpty()) {
            ups = victoriaMetricsClient.queryInstant("up");
        }
        if (ups.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (VictoriaMetricsClient.InstantSample s : ups) {
            String job = label(s, "job");
            String inst = label(s, "instance");
            if (StrUtil.isBlank(job) && StrUtil.isBlank(inst)) {
                continue;
            }
            boolean ok = s.value() >= 1.0;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("comp", StrUtil.blankToDefault(job, "process"));
            row.put("inst", StrUtil.blankToDefault(inst, "-"));
            row.put("st", ok ? "ok" : "down");
            row.put("metric", ok ? "up=1" : "up=0");
            row.put("act", ok ? "链路→" : "重试");
            rows.add(row);
            if (rows.size() >= 40) {
                break;
            }
        }
        return rows;
    }

    private List<Map<String, Object>> loadProcsFromHttpProbe() {
        List<Map<String, Object>> rows = new ArrayList<>();
        probe(rows, "Trino", lhProperties.getTrino() == null ? null : lhProperties.getTrino().getUrl(), "/v1/info");
        probe(rows, "Gravitino", lhProperties.getGravitino() == null ? null : lhProperties.getGravitino().getUrl(), "/");
        probe(rows, "MinIO", lhProperties.getMinio() == null ? null : lhProperties.getMinio().getUrl(), "/minio/health/live");
        probe(rows, "OpenMetadata", lhProperties.getOpenmetadata() == null ? null : lhProperties.getOpenmetadata().getUrl(), "/");
        probe(rows, "DolphinScheduler", lhProperties.getDs() == null ? null : lhProperties.getDs().getUrl(), "/");
        probe(rows, "Flink", lhProperties.getFlink() == null ? null : lhProperties.getFlink().getUrl(), "/overview");
        return rows;
    }

    private void probe(List<Map<String, Object>> rows, String comp, String baseUrl, String path) {
        if (StrUtil.isBlank(baseUrl)) {
            return;
        }
        String base = StrUtil.removeSuffix(baseUrl.trim(), "/");
        String url = base + (path == null ? "" : path);
        boolean ok = false;
        String metric = "unreachable";
        try {
            HttpResponse resp = HttpRequest.get(url).timeout(2500).execute();
            int code = resp.getStatus();
            ok = code >= 200 && code < 500;
            metric = "HTTP " + code;
        } catch (Exception e) {
            metric = StrUtil.maxLength(e.getMessage(), 48);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("comp", comp);
        row.put("inst", hostOf(base.replaceFirst("^https?://", "")));
        row.put("st", ok ? "ok" : "down");
        row.put("metric", metric);
        row.put("act", ok ? "链路→" : "重试");
        if ("MinIO".equals(comp)) {
            row.put("act", "扩容");
        }
        rows.add(row);
    }

    private void addAlertSamples(
            List<Map<String, Object>> out,
            String promQl,
            String sev,
            String domain,
            String titlePrefix,
            String act) {
        List<VictoriaMetricsClient.InstantSample> samples = victoriaMetricsClient.queryInstant(promQl);
        for (VictoriaMetricsClient.InstantSample s : samples) {
            String host = firstLabel(s, "instance", "bucket", "fqtn", "ws", "job");
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("sev", sev);
            a.put("t", titlePrefix + (StrUtil.isNotBlank(host) ? " · " + host : ""));
            a.put("time", "即时评估");
            a.put("host", StrUtil.blankToDefault(host, "-"));
            a.put("act", act);
            a.put("live", true);
            a.put("domain", domain);
            a.put("value", s.value());
            out.add(a);
            if (out.size() >= 50) {
                return;
            }
        }
    }

    private static Map<String, Double> indexByInstance(List<VictoriaMetricsClient.InstantSample> samples) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (samples == null) {
            return out;
        }
        for (VictoriaMetricsClient.InstantSample s : samples) {
            String inst = label(s, "instance");
            if (StrUtil.isBlank(inst)) {
                inst = label(s, "host");
            }
            if (StrUtil.isBlank(inst)) {
                continue;
            }
            out.put(inst, s.value());
        }
        return out;
    }

    private static Map<String, Double> indexByKey(List<VictoriaMetricsClient.InstantSample> samples, String... labelKeys) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (samples == null) {
            return out;
        }
        for (VictoriaMetricsClient.InstantSample s : samples) {
            String primary = firstLabel(s, labelKeys);
            if (StrUtil.isBlank(primary)) {
                continue;
            }
            String inst = firstLabel(s, "instance", "namespace");
            String key = primary + "@" + StrUtil.blankToDefault(inst, "");
            out.put(key, s.value());
            out.putIfAbsent(primary, s.value());
        }
        return out;
    }

    private static String humanBytes(double bytes) {
        if (bytes < 1024) {
            return String.format(Locale.ROOT, "%.0f B", bytes);
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024 * 1024));
        }
        return String.format(Locale.ROOT, "%.1f GB", bytes / (1024 * 1024 * 1024));
    }

    private static String label(VictoriaMetricsClient.InstantSample s, String key) {
        if (s == null || s.labels() == null) {
            return null;
        }
        return s.labels().get(key);
    }

    private static String firstLabel(VictoriaMetricsClient.InstantSample s, String... keys) {
        if (s == null || s.labels() == null) {
            return null;
        }
        for (String k : keys) {
            String v = s.labels().get(k);
            if (StrUtil.isNotBlank(v)) {
                return v;
            }
        }
        return null;
    }

    private static int pct(Double v) {
        if (v == null || Double.isNaN(v) || Double.isInfinite(v)) {
            return 0;
        }
        return (int) Math.max(0, Math.min(100, Math.round(v)));
    }

    private static String hostOf(String instance) {
        if (StrUtil.isBlank(instance)) {
            return "-";
        }
        int slash = instance.indexOf('/');
        String s = slash > 0 ? instance.substring(0, slash) : instance;
        int colon = s.lastIndexOf(':');
        if (colon > 0 && s.indexOf(']') < 0) {
            return s.substring(0, colon);
        }
        return s;
    }

    private static String formatNet(Double rx, Double tx) {
        if (rx == null && tx == null) {
            return "—";
        }
        return "↓" + humanRate(rx) + " ↑" + humanRate(tx);
    }

    private static String humanRate(Double bps) {
        if (bps == null || bps < 0 || Double.isNaN(bps)) {
            return "—";
        }
        if (bps < 1024) {
            return String.format(Locale.ROOT, "%.0fB/s", bps);
        }
        if (bps < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1fKB/s", bps / 1024);
        }
        return String.format(Locale.ROOT, "%.1fMB/s", bps / (1024 * 1024));
    }
}
