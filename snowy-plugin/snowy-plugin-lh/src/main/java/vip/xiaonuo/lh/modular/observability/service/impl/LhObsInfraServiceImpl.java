package vip.xiaonuo.lh.modular.observability.service.impl;

import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import vip.xiaonuo.lh.core.engine.NightingaleClient;
import vip.xiaonuo.lh.modular.lifecycle.support.GovLcBucketMetricsReader;
import vip.xiaonuo.lh.modular.observability.service.LhObsInfraService;
import vip.xiaonuo.lh.modular.observability.support.LhObsInfraMetricsSupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基础设施监控门面（§30）：优先 VictoriaMetrics + 夜莺事件 + 组件探针；无采集时合法空态。
 */
@Service
public class LhObsInfraServiceImpl implements LhObsInfraService {

    @Resource
    private LhObsInfraMetricsSupport infraMetricsSupport;
    @Resource
    private GovLcBucketMetricsReader bucketMetricsReader;
    @Resource
    private NightingaleClient nightingaleClient;

    @Override
    public Map<String, Object> summary(String ws) {
        List<Map<String, Object>> nodes = infraMetricsSupport.loadNodes();
        List<Map<String, Object>> procs = infraMetricsSupport.loadProcs();
        List<Map<String, Object>> alerts = mergeAlerts();
        List<Map<String, Object>> tips = infraMetricsSupport.capacityTipsFromBuckets();
        List<Map<String, Object>> containers = infraMetricsSupport.loadContainers();
        List<Map<String, Object>> cluster = infraMetricsSupport.loadCluster();
        boolean hasBuckets = !tips.isEmpty()
                || (bucketMetricsReader.available() && !bucketMetricsReader.listBuckets().isEmpty());
        boolean hasVm = infraMetricsSupport.vmConfigured();
        boolean hasData = !nodes.isEmpty() || !procs.isEmpty() || !alerts.isEmpty() || hasBuckets
                || !containers.isEmpty() || !cluster.isEmpty();

        Map<String, Object> r = base(ws);
        r.put("kpis", buildKpis(nodes, procs, alerts, hasBuckets));
        r.put("capacityTips", tips);
        r.put("containerCount", containers.size());
        r.put("clusterCount", cluster.size());
        if (!hasData) {
            r.put("source", "empty");
            r.put("hint", hasVm
                    ? "已配 VM，但暂无 node/up/存储/容器点；确认 Categraf 抓取或日批写点后刷新"
                    : "无 Categraf/VictoriaMetrics/夜莺采集时为空态；禁止演示节点与假告警");
        } else {
            r.put("source", sourceTag(nodes, procs, alerts, hasBuckets, containers, cluster));
            r.put("hint", "节点=node_exporter；进程=up/HTTP；告警=n9e∪VM PromQL；L1/L2=cadvisor/kube-state（有点则填）");
        }
        return r;
    }

    @Override
    public Map<String, Object> nodes(String ws) {
        List<Map<String, Object>> records = infraMetricsSupport.loadNodes();
        Map<String, Object> r = base(ws);
        r.put("records", records);
        r.put("source", records.isEmpty() ? "empty" : "vm:node_exporter");
        return r;
    }

    @Override
    public Map<String, Object> procs(String ws) {
        List<Map<String, Object>> records = infraMetricsSupport.loadProcs();
        Map<String, Object> r = base(ws);
        r.put("records", records);
        String src = "empty";
        if (!records.isEmpty()) {
            Object metric = records.get(0).get("metric");
            src = metric != null && String.valueOf(metric).startsWith("up=")
                    ? "vm:up"
                    : "http_probe";
        }
        r.put("source", src);
        return r;
    }

    @Override
    public Map<String, Object> alerts(String ws) {
        List<Map<String, Object>> records = mergeAlerts();
        Map<String, Object> r = base(ws);
        r.put("records", records);
        boolean n9e = nightingaleClient.configured()
                && records.stream().anyMatch(a -> "n9e".equals(a.get("source")));
        boolean vm = records.stream().anyMatch(a -> !"n9e".equals(a.get("source")));
        String src = "empty";
        if (n9e && vm) {
            src = "n9e+vm:promql";
        } else if (n9e) {
            src = "n9e";
        } else if (vm) {
            src = "vm:promql";
        }
        r.put("source", src);
        r.put("hint", n9e
                ? "优先夜莺当前事件；VM PromQL 作补充（未进夜莺的瞬时条件）"
                : "未配夜莺 URL/凭证时仅 VM PromQL；通知仍建议走夜莺规则包");
        return r;
    }

    @Override
    public Map<String, Object> containers(String ws) {
        List<Map<String, Object>> records = infraMetricsSupport.loadContainers();
        Map<String, Object> r = base(ws);
        r.put("records", records);
        r.put("layer", "L1");
        r.put("source", records.isEmpty() ? "empty" : "vm:cadvisor");
        r.put("hint", records.isEmpty()
                ? "§30.5 L1：无 cadvisor 指标时空列表合法"
                : "来自 container_cpu_usage_seconds_total / container_memory_working_set_bytes");
        return r;
    }

    @Override
    public Map<String, Object> cluster(String ws) {
        List<Map<String, Object>> records = infraMetricsSupport.loadCluster();
        Map<String, Object> r = base(ws);
        r.put("records", records);
        r.put("layer", "L2");
        r.put("source", records.isEmpty() ? "empty" : "vm:kube-state");
        r.put("hint", records.isEmpty()
                ? "§30.5 L2：无 kube-state-metrics 时空列表合法"
                : "来自 kube_node_status_condition / kube_deployment_*");
        return r;
    }

    @Override
    public Map<String, Object> capacityForecast(String ws) {
        List<Map<String, Object>> tips = infraMetricsSupport.capacityTipsFromBuckets();
        Map<String, Object> r = base(ws);
        r.put("points", List.of());
        r.put("horizonDays", null);
        r.put("tips", tips);
        r.put("source", tips.isEmpty() ? "empty" : "vm:bucket");
        r.put("hint", tips.isEmpty()
                ? "§30.5 容量预测曲线归存储趋势；无桶点时合法空态"
                : "一期用桶水位建议（深链存储趋势）；预测曲线仍归 /lifecycle/storage");
        return r;
    }

    private List<Map<String, Object>> mergeAlerts() {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Map<String, Object>> n9e = nightingaleClient.listCurrentAlerts();
        out.addAll(n9e);
        List<Map<String, Object>> vm = infraMetricsSupport.loadAlertsFromVm();
        // 夜莺已有同名规则时，VM 瞬时评估作补充；简单按标题去重
        for (Map<String, Object> a : vm) {
            String t = String.valueOf(a.getOrDefault("t", ""));
            boolean dup = out.stream().anyMatch(x -> String.valueOf(x.getOrDefault("t", "")).contains(
                    t.length() > 12 ? t.substring(0, 12) : t));
            if (!dup) {
                a.putIfAbsent("source", "vm:promql");
                out.add(a);
            }
            if (out.size() >= 80) {
                break;
            }
        }
        return out;
    }

    private static Map<String, Object> base(String ws) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("source", "empty");
        r.put("ws", ws == null || ws.isBlank() ? null : ws.trim());
        return r;
    }

    private static List<Map<String, Object>> buildKpis(
            List<Map<String, Object>> nodes,
            List<Map<String, Object>> procs,
            List<Map<String, Object>> alerts,
            boolean hasBuckets) {
        if (nodes.isEmpty() && procs.isEmpty() && alerts.isEmpty() && !hasBuckets) {
            return emptyKpis();
        }
        List<Map<String, Object>> list = new ArrayList<>();
        if (nodes.isEmpty()) {
            list.add(kpi("🖥️", "blue", "—", "台", "节点", "无 node 指标", true, false, false));
        } else {
            long warn = nodes.stream().filter(n -> !"ok".equals(n.get("st"))).count();
            list.add(kpi("🖥️", "blue", String.valueOf(nodes.size()), "台", "节点",
                    warn > 0 ? warn + " 告警/异常" : "采集中", warn == 0, warn > 0, false));
        }

        if (procs.isEmpty()) {
            list.add(kpi("✅", "green", "—", "%", "组件进程存活", "无探针", true, false, false));
        } else {
            long ok = procs.stream().filter(p -> "ok".equals(p.get("st"))).count();
            int pct = (int) Math.round(100.0 * ok / procs.size());
            list.add(kpi("✅", "green", String.valueOf(pct), "%", "组件进程存活",
                    ok + "/" + procs.size() + " 存活", pct >= 80, pct < 80 && pct >= 50, pct < 50));
        }

        list.add(kpi("🚨", "red",
                alerts.isEmpty() ? "0" : String.valueOf(alerts.size()),
                "条", "活跃告警",
                alerts.isEmpty() ? "无触发" : "n9e/VM",
                alerts.isEmpty(), false, !alerts.isEmpty()));

        Double avgDisk = avgDisk(nodes);
        if (avgDisk == null && !hasBuckets) {
            list.add(kpi("💽", "orange", "—", "%", "平均磁盘水位", "无采集", false, true, false));
        } else if (avgDisk != null) {
            int d = (int) Math.round(avgDisk);
            list.add(kpi("💽", "orange", String.valueOf(d), "%", "平均磁盘水位",
                    "node filesystem", d < 70, d >= 70 && d < 85, d >= 85));
        } else {
            list.add(kpi("💽", "orange", "—", "%", "平均磁盘水位", "见桶水位建议", false, true, false));
        }
        return list;
    }

    private static Double avgDisk(List<Map<String, Object>> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return null;
        }
        double sum = 0;
        int n = 0;
        for (Map<String, Object> row : nodes) {
            Object d = row.get("disk");
            if (d instanceof Number num) {
                sum += num.doubleValue();
                n++;
            }
        }
        return n == 0 ? null : sum / n;
    }

    private static String sourceTag(
            List<Map<String, Object>> nodes,
            List<Map<String, Object>> procs,
            List<Map<String, Object>> alerts,
            boolean hasBuckets,
            List<Map<String, Object>> containers,
            List<Map<String, Object>> cluster) {
        List<String> parts = new ArrayList<>();
        if (!nodes.isEmpty()) {
            parts.add("node");
        }
        if (!procs.isEmpty()) {
            parts.add("proc");
        }
        if (!alerts.isEmpty()) {
            parts.add("alert");
        }
        if (hasBuckets) {
            parts.add("bucket");
        }
        if (!containers.isEmpty()) {
            parts.add("L1");
        }
        if (!cluster.isEmpty()) {
            parts.add("L2");
        }
        return "vm:" + String.join("+", parts);
    }

    private static List<Map<String, Object>> emptyKpis() {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(kpi("🖥️", "blue", "—", "台", "节点", "无采集", true, false, false));
        list.add(kpi("✅", "green", "—", "%", "组件进程存活", "无采集", true, false, false));
        list.add(kpi("🚨", "red", "—", "条", "活跃告警", "无采集", false, false, true));
        list.add(kpi("💽", "orange", "—", "%", "平均磁盘水位", "无采集", false, true, false));
        return list;
    }

    private static Map<String, Object> kpi(
            String icon, String color, String value, String unit, String label,
            String trend, boolean trendUp, boolean trendWarn, boolean trendDanger) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("icon", icon);
        m.put("color", color);
        m.put("value", value);
        m.put("unit", unit);
        m.put("label", label);
        m.put("trend", trend);
        m.put("trendUp", trendUp);
        m.put("trendWarn", trendWarn);
        m.put("trendDanger", trendDanger);
        return m;
    }
}
