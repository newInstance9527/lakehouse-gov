package vip.xiaonuo.lh.modular.observability.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.engine.TrinoClient;
import vip.xiaonuo.lh.core.vault.LhComponentCredentialResolver;
import vip.xiaonuo.lh.modular.observability.service.LhObsTrinoService;
import vip.xiaonuo.lh.modular.query.param.CpQueryHistoryParam;
import vip.xiaonuo.lh.modular.query.service.CpQueryService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class LhObsTrinoServiceImpl implements LhObsTrinoService {

    private static final Logger log = LoggerFactory.getLogger(LhObsTrinoServiceImpl.class);

    @Resource
    private CpQueryService cpQueryService;
    @Resource
    private TrinoClient trinoClient;
    @Resource
    private LhProperties lhProperties;
    @Resource
    private LhComponentCredentialResolver credentialResolver;

    @Override
    public Map<String, Object> queues(String ws) {
        Map<String, Object> overview = cpQueryService.govOverview();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> queues = overview.get("queues") instanceof List<?> list
                ? new ArrayList<>((List<Map<String, Object>>) list)
                : new ArrayList<>();

        Map<String, Integer> liveByQueue = loadLiveQueueCounts();
        Map<String, Integer> cluster = loadClusterCounts();
        boolean live = !liveByQueue.isEmpty() || !cluster.isEmpty();
        for (Map<String, Object> q : queues) {
            String name = StrUtil.blankToDefault(str(q.get("name")), "");
            int running = liveByQueue.getOrDefault(name, 0);
            // adhoc 仍用门户并发闸门作兜底
            if ("adhoc".equals(name) && running == 0 && overview.get("adhocConcurrent") instanceof Number n) {
                running = n.intValue();
            }
            q.put("running", running);
            q.put("qps", "在途 " + running);
            if (live) {
                q.put("liveSource", liveByQueue.isEmpty() ? "trino:/v1/cluster" : "system.runtime.queries");
            }
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ws", blank(ws));
        r.put("queues", queues);
        r.put("adhocMaxConcurrent", overview.get("adhocMaxConcurrent"));
        r.put("adhocConcurrent", overview.get("adhocConcurrent"));
        r.put("liveByQueue", liveByQueue);
        r.put("cluster", cluster);
        r.put("source", live
                ? (liveByQueue.isEmpty() ? "trino:cluster+cp_query_gov" : "trino:runtime.queries+cluster+cp_query_gov")
                : "cp_query_gov_overview");
        r.put("hint", live
                ? "在途来自 system.runtime.queries 与/或 /v1/cluster；限额仍为门户配置（JMX 非必需）"
                : "未读到 runtime.queries /v1/cluster；回落配置型队列 + 门户 adhoc 闸门");
        return r;
    }

    @Override
    public Map<String, Object> topUsers(String ws, String range) {
        CpQueryHistoryParam hp = new CpQueryHistoryParam();
        hp.setLimit(200);
        hp.setMineOnly(false);
        List<Map<String, Object>> audits = cpQueryService.history(hp);
        Map<String, long[]> agg = new HashMap<>();
        for (Map<String, Object> a : audits) {
            String user = StrUtil.blankToDefault(str(a.get("user")), str(a.get("createUser")));
            if (StrUtil.isBlank(user)) {
                user = "unknown";
            }
            long bytes = asLong(a.get("scanBytes"));
            long[] slot = agg.computeIfAbsent(user, k -> new long[2]);
            slot[0]++;
            slot[1] += bytes;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, long[]> e : agg.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("user", e.getKey());
            row.put("queryCount", e.getValue()[0]);
            row.put("scanBytes", e.getValue()[1]);
            rows.add(row);
        }
        rows.sort(Comparator.comparingLong((Map<String, Object> m) -> asLong(m.get("scanBytes"))).reversed());
        if (rows.size() > 20) {
            rows = rows.subList(0, 20);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ws", blank(ws));
        r.put("range", StrUtil.blankToDefault(range, "recent"));
        r.put("records", rows);
        r.put("total", rows.size());
        r.put("source", rows.isEmpty() ? "empty" : "cp_query_exec");
        return r;
    }

    @Override
    public Map<String, Object> slow(String ws, String range, Integer limit) {
        int n = limit == null ? 20 : Math.max(1, Math.min(limit, 100));
        CpQueryHistoryParam hp = new CpQueryHistoryParam();
        hp.setLimit(200);
        hp.setMineOnly(false);
        List<Map<String, Object>> audits = new ArrayList<>(cpQueryService.history(hp));
        audits.sort(Comparator
                .comparingLong((Map<String, Object> a) -> asLong(a.get("durationMs"))).reversed()
                .thenComparingLong((Map<String, Object> a) -> asLong(a.get("scanBytes"))).reversed());
        List<Map<String, Object>> slow = audits.size() > n ? audits.subList(0, n) : audits;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ws", blank(ws));
        r.put("range", StrUtil.blankToDefault(range, "recent"));
        r.put("records", slow);
        r.put("total", slow.size());
        r.put("source", slow.isEmpty() ? "empty" : "cp_query_exec");
        return r;
    }

    /**
     * 读 Trino 在途查询，按 source 粗分到 dashboard / adhoc / etl；
     * 优先 {@code system.runtime.queries}，并用 {@code GET /v1/cluster} 补总在途（JMX 可选后置）。
     */
    private Map<String, Integer> loadLiveQueueCounts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("dashboard", 0);
        out.put("adhoc", 0);
        out.put("etl", 0);
        boolean fromSql = false;
        try {
            TrinoClient.ExecuteOptions opts = TrinoClient.ExecuteOptions.job(200);
            opts.catalog = "system";
            opts.schema = "runtime";
            opts.source = "lakehouse-obs-queues";
            opts.timeoutMs = 12_000;
            Map<String, Object> res = trinoClient.execute(
                    "SELECT source, state, count(*) AS c "
                            + "FROM system.runtime.queries "
                            + "WHERE state NOT IN ('FINISHED','FAILED','CANCELED') "
                            + "GROUP BY 1, 2",
                    opts);
            if (!Boolean.TRUE.equals(res.get("degraded"))) {
                fromSql = true;
                Object rowsObj = res.get("rows");
                if (rowsObj instanceof List<?> rows) {
                    for (Object rowObj : rows) {
                        if (!(rowObj instanceof Map<?, ?> row)) {
                            continue;
                        }
                        String source = str(row.get("source"));
                        int c = (int) asLong(row.get("c"));
                        if (c <= 0) {
                            c = (int) asLong(row.get("_col2"));
                        }
                        String queue = mapSourceToQueue(source);
                        out.merge(queue, Math.max(c, 0), Integer::sum);
                    }
                }
            } else {
                log.debug("trino runtime.queries degraded: {}", res.get("message"));
            }
        } catch (Exception e) {
            log.debug("trino runtime.queries failed: {}", e.getMessage());
        }
        // /v1/cluster：总 running+queued；无分队列时摊到 adhoc
        Map<String, Integer> cluster = loadClusterCounts();
        if (!cluster.isEmpty()) {
            int running = cluster.getOrDefault("running", 0);
            int queued = cluster.getOrDefault("queued", 0);
            int totalLive = running + queued;
            int mapped = out.values().stream().mapToInt(Integer::intValue).sum();
            if (!fromSql || mapped == 0) {
                out.put("adhoc", totalLive);
            }
            out.put("_clusterRunning", running);
            out.put("_clusterQueued", queued);
        }
        if (!fromSql && cluster.isEmpty()) {
            return Map.of();
        }
        // 去掉内部键再给调用方；但 queues() 需要 live 标记，保留 cluster 信息在单独字段更清晰
        Map<String, Integer> clean = new LinkedHashMap<>();
        clean.put("dashboard", out.getOrDefault("dashboard", 0));
        clean.put("adhoc", out.getOrDefault("adhoc", 0));
        clean.put("etl", out.getOrDefault("etl", 0));
        return clean;
    }

    private Map<String, Integer> loadClusterCounts() {
        try {
            String base = StrUtil.removeSuffix(
                    StrUtil.nullToEmpty(lhProperties.getTrino() == null ? null : lhProperties.getTrino().getUrl()),
                    "/");
            if (StrUtil.isBlank(base)) {
                return Map.of();
            }
            Map<String, String> cred = credentialResolver.trino();
            HttpResponse resp = HttpRequest.get(base + "/v1/cluster")
                    .basicAuth(cred.get("username"), cred.get("password"))
                    .timeout(5_000)
                    .execute();
            if (resp.getStatus() < 200 || resp.getStatus() >= 300) {
                return Map.of();
            }
            JSONObject body = JSONUtil.parseObj(resp.body());
            Map<String, Integer> m = new LinkedHashMap<>();
            m.put("running", body.getInt("runningQueries", 0));
            m.put("queued", body.getInt("queuedQueries", 0));
            m.put("blocked", body.getInt("blockedQueries", 0));
            return m;
        } catch (Exception e) {
            log.debug("trino /v1/cluster failed: {}", e.getMessage());
            return Map.of();
        }
    }

    static String mapSourceToQueue(String source) {
        String s = StrUtil.blankToDefault(source, "").toLowerCase(Locale.ROOT);
        if (s.contains("superset") || s.contains("dashboard") || s.contains("bi")) {
            return "dashboard";
        }
        if (s.contains("etl") || s.contains("ds") || s.contains("flink") || s.contains("spark")
                || s.contains("batch") || s.contains("job")) {
            return "etl";
        }
        return "adhoc";
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String blank(String ws) {
        return StrUtil.isBlank(ws) ? null : ws.trim();
    }

    private static long asLong(Object o) {
        if (o == null) {
            return 0L;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return 0L;
        }
    }
}
