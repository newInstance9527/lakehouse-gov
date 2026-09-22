package vip.xiaonuo.lh.modular.lifecycle.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcOrphanScan;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcPolicy;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcStorageAdvice;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcStorageChangePoint;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcTableStat;
import vip.xiaonuo.lh.modular.lifecycle.mapper.GovLcOrphanScanMapper;
import vip.xiaonuo.lh.modular.lifecycle.mapper.GovLcPolicyMapper;
import vip.xiaonuo.lh.modular.lifecycle.mapper.GovLcStorageAdviceMapper;
import vip.xiaonuo.lh.modular.lifecycle.mapper.GovLcStorageChangePointMapper;
import vip.xiaonuo.lh.modular.lifecycle.mapper.GovLcTableStatMapper;
import vip.xiaonuo.lh.modular.lifecycle.service.GovLcStorageService;
import vip.xiaonuo.lh.modular.lifecycle.support.GovLcBucketMetricsReader;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 存储趋势 P0：三口径从 {@code gov_lc_table_stat} 派生；建议/变更点读真表；VM 接入后替换派生逻辑。
 */
@Service
public class GovLcStorageServiceImpl implements GovLcStorageService {

    private static final String WS_DEFAULT = "default";
    private static final String NOT_DELETE = "NOT_DELETE";
    private static final long GB = 1024L * 1024 * 1024;
    private static final long TB = GB * 1024;
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("MM-dd");

    @Resource
    private GovLcTableStatMapper tableStatMapper;
    @Resource
    private GovLcPolicyMapper policyMapper;
    @Resource
    private GovLcOrphanScanMapper orphanScanMapper;
    @Resource
    private GovLcStorageAdviceMapper adviceMapper;
    @Resource
    private GovLcStorageChangePointMapper changePointMapper;
    @Resource
    private GovLcBucketMetricsReader bucketMetricsReader;

    @Override
    public Map<String, Object> summary(String ws, String range) {
        String workspace = wsOrDefault(ws);
        int days = parseRangeDays(range);
        List<TableCaliber> rows = buildCalibers(workspace, days);

        long active = rows.stream().mapToLong(TableCaliber::activeBytes).sum();
        long reclaimable = rows.stream().mapToLong(TableCaliber::reclaimableBytes).sum();
        long total = active + reclaimable;
        long netGrowth = rows.stream().mapToLong(TableCaliber::netGrowthBytes).sum();

        List<Map<String, Object>> bucketList = buckets(workspace);
        Map<String, Object> tightest = bucketList.stream()
                .filter(b -> b.get("daysToFullP95") instanceof Number)
                .min(Comparator.comparingDouble(b -> ((Number) b.get("daysToFullP95")).doubleValue()))
                .orElse(null);

        Date latestCollect = rows.stream()
                .map(TableCaliber::collectedAt)
                .filter(Objects::nonNull)
                .max(Date::compareTo)
                .orElse(null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ws", workspace);
        out.put("range", rangeLabel(days));
        out.put("rangeDays", days);
        out.put("physicalBytes", total);
        out.put("activeBytes", active);
        out.put("reclaimableBytes", reclaimable);
        out.put("reclaimablePct", pct(reclaimable, total));
        out.put("netGrowthBytes", netGrowth);
        out.put("tightestBucket", tightest);
        out.put("collectStatus", latestCollect == null ? "STALE" : "FRESH");
        out.put("collectedAt", latestCollect);
        long profiled = rows.stream().filter(TableCaliber::profiled).count();
        out.put("profiledTables", profiled);
        out.put("source", profiled > 0
                ? "gov_lc_table_stat(trino $files/$snapshots); 桶级见 buckets.source"
                : "gov_lc_table_stat(seed)+gov_lc_storage_*; 桶级见 buckets.source");
        out.put("caliberNote", "active+reclaimable=physical（P0 派生；正式由 lh_table_storage_* 承接）");
        out.put("bucketSource", bucketMetricsReader.available() ? "vm-or-seed" : "seed");
        return out;
    }

    @Override
    public Map<String, Object> trend(String ws, String range, String group) {
        String workspace = wsOrDefault(ws);
        int days = parseRangeDays(range);
        String grp = StrUtil.blankToDefault(group, "layer").toLowerCase(Locale.ROOT);
        List<TableCaliber> rows = buildCalibers(workspace, days);

        long activeNow = rows.stream().mapToLong(TableCaliber::activeBytes).sum();
        long reclaimNow = rows.stream().mapToLong(TableCaliber::reclaimableBytes).sum();
        long totalNow = activeNow + reclaimNow;

        List<Map<String, Object>> daily = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = days - 1; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            double factor = 1.0 - (i * (0.0035));
            long total = Math.round(totalNow * factor);
            long active = Math.round(activeNow * factor);
            long reclaim = Math.max(0, total - active);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("day", d.format(DAY_FMT));
            point.put("date", d.toString());
            point.put("totalBytes", total);
            point.put("activeBytes", active);
            point.put("reclaimableBytes", reclaim);
            daily.add(point);
        }

        List<Map<String, Object>> series;
        if ("bucket".equals(grp)) {
            series = buckets(workspace).stream().map(b -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", b.get("bucket"));
                m.put("totalBytes", b.get("usedBytes"));
                m.put("label", b.get("bucket"));
                return m;
            }).toList();
        } else if ("ws".equals(grp)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", workspace);
            m.put("label", workspace);
            m.put("activeBytes", activeNow);
            m.put("totalBytes", totalNow);
            m.put("reclaimableBytes", reclaimNow);
            series = List.of(m);
        } else {
            Map<String, long[]> layerAgg = new LinkedHashMap<>();
            for (TableCaliber r : rows) {
                String layer = StrUtil.blankToDefault(r.layer(), "OTHER");
                layerAgg.computeIfAbsent(layer, k -> new long[3]);
                long[] a = layerAgg.get(layer);
                a[0] += r.activeBytes();
                a[1] += r.totalBytes();
                a[2] += r.reclaimableBytes();
            }
            long sumActive = Math.max(1, activeNow);
            series = layerAgg.entrySet().stream().map(e -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", e.getKey());
                m.put("layer", e.getKey());
                m.put("activeBytes", e.getValue()[0]);
                m.put("totalBytes", e.getValue()[1]);
                m.put("reclaimableBytes", e.getValue()[2]);
                m.put("pct", pct(e.getValue()[0], sumActive));
                m.put("growthPct", growthForLayer(rows, e.getKey()));
                m.put("netGrowthBytes", netForLayer(rows, e.getKey()));
                return m;
            }).toList();
        }

        List<Map<String, Object>> changePoints = listChangePoints(workspace, days).stream()
                .map(this::changePointToMap)
                .toList();

        Map<String, Object> forecast = new LinkedHashMap<>();
        if (days >= 15) {
            forecast.put("available", true);
            forecast.put("p50DaysToFull", 62);
            forecast.put("p95DaysToFull", 48);
            forecast.put("note", "API stub；日批已派生 lh_table_storage_days_to_full 写 VM（现网 VM URL 未配则跳过）");
        } else {
            forecast.put("available", false);
            forecast.put("reason", "INSUFFICIENT");
            forecast.put("note", "样本不足 15 天，不出预测");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ws", workspace);
        out.put("range", rangeLabel(days));
        out.put("rangeDays", days);
        out.put("group", grp);
        out.put("daily", daily);
        out.put("series", series);
        out.put("changePoints", changePoints);
        out.put("forecast", forecast);
        out.put("source", "bucket".equals(grp)
                ? (bucketMetricsReader.available()
                ? "lh_bucket_storage_*|minio_* via VM; seed fallback"
                : "seed buckets; configure lh.lifecycle.vm-import-url + Categraf")
                : "gov_lc_table_stat(seed); table series VM P1");
        return out;
    }

    @Override
    public Map<String, Object> tables(String ws, String range, String layer, String filter,
                                      String sort, String order, Integer page, Integer size) {
        String workspace = wsOrDefault(ws);
        int days = parseRangeDays(range);
        List<TableCaliber> rows = buildCalibers(workspace, days);

        String layerF = StrUtil.trimToNull(layer);
        if (layerF != null) {
            rows = rows.stream().filter(r -> layerF.equalsIgnoreCase(r.layer())).collect(Collectors.toList());
        }

        String f = StrUtil.blankToDefault(filter, "all").toLowerCase(Locale.ROOT);
        rows = switch (f) {
            case "anomaly" -> rows.stream().filter(TableCaliber::anomaly).collect(Collectors.toList());
            case "reclaimable" -> rows.stream()
                    .filter(r -> r.totalBytes() > 0 && (r.reclaimableBytes() * 100.0 / r.totalBytes()) > 20)
                    .collect(Collectors.toList());
            case "smallfile" -> rows.stream().filter(r -> r.smallFileRatio() > 0.30)
                    .collect(Collectors.toList());
            default -> rows;
        };

        String sortKey = StrUtil.blankToDefault(sort, "reclaimableBytes");
        boolean asc = "asc".equalsIgnoreCase(order);
        Comparator<TableCaliber> cmp = comparator(sortKey);
        if (asc) {
            rows.sort(cmp);
        } else {
            rows.sort(cmp.reversed());
        }

        int pageNo = page == null || page < 1 ? 1 : page;
        int pageSize = size == null || size < 1 ? 20 : Math.min(size, 200);
        int from = Math.min((pageNo - 1) * pageSize, rows.size());
        int to = Math.min(from + pageSize, rows.size());
        List<Map<String, Object>> list = rows.subList(from, to).stream()
                .map(this::caliberToTableRow)
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ws", workspace);
        out.put("range", rangeLabel(days));
        out.put("page", pageNo);
        out.put("size", pageSize);
        out.put("total", rows.size());
        out.put("list", list);
        return out;
    }

    @Override
    public Map<String, Object> tableDetail(String ws, String fqtn, String range) {
        if (StrUtil.isBlank(fqtn)) {
            throw new CommonException("fqtn 不能为空");
        }
        String workspace = wsOrDefault(ws);
        int days = parseRangeDays(StrUtil.blankToDefault(range, "90d"));
        String fqn = fqtn.trim();

        TableCaliber hit = buildCalibers(workspace, days).stream()
                .filter(r -> fqn.equals(r.fqtn()))
                .findFirst()
                .orElse(null);
        if (hit == null) {
            throw new CommonException("未找到表存储投影: " + fqn);
        }

        List<Map<String, Object>> curve = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = days - 1; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            double factor = 1.0 - (i * 0.004);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("date", d.toString());
            p.put("activeBytes", Math.round(hit.activeBytes() * factor));
            p.put("totalBytes", Math.round(hit.totalBytes() * factor));
            p.put("reclaimableBytes", Math.round(hit.reclaimableBytes() * factor));
            curve.add(p);
        }

        GovLcPolicy policy = policyMapper.selectOne(new QueryWrapper<GovLcPolicy>().lambda()
                .eq(GovLcPolicy::getWs, workspace)
                .eq(GovLcPolicy::getTableFqn, fqn)
                .eq(GovLcPolicy::getDeleteFlag, NOT_DELETE)
                .last("LIMIT 1"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ws", workspace);
        out.put("fqtn", fqn);
        out.put("range", rangeLabel(days));
        out.put("row", caliberToTableRow(hit));
        out.put("curve", curve);
        out.put("partitionHint", Map.of(
                "note", "P0 stub；正式查 Iceberg $partitions",
                "partitionCount", hit.partitionCount()
        ));
        out.put("policy", policy == null ? null : Map.of(
                "keepCount", policy.getKeepCount(),
                "keepDays", policy.getKeepDays(),
                "compactLevel", policy.getCompactLevel(),
                "targetFileMb", policy.getTargetFileMb()
        ));
        out.put("deepLink", Map.of(
                "lifecycle", "/lifecycle?table=" + fqn + "&from=storage-trend",
                "catalog", "/catalog?q=" + fqn
        ));
        return out;
    }

    @Override
    public List<Map<String, Object>> buckets(String ws) {
        String workspace = wsOrDefault(ws);
        List<Map<String, Object>> list = new ArrayList<>();
        List<GovLcBucketMetricsReader.BucketSnapshot> fromVm = bucketMetricsReader.listBuckets();
        if (!fromVm.isEmpty()) {
            long nowSec = System.currentTimeMillis() / 1000L;
            for (GovLcBucketMetricsReader.BucketSnapshot snap : fromVm) {
                list.add(bucketFromVm(snap, nowSec));
            }
        } else {
            // 无 VM / 无点：对齐演示 + §11 热/温/冷；正式读 Categraf→VM
            list.add(bucket("iceberg-ods", 1.2 * TB, 8L * TB, 62, "warm", "seed"));
            list.add(bucket("iceberg-dwd", 1.6 * TB, 8L * TB, 148, "warm", "seed"));
            list.add(bucket("iceberg-dws", 0.3 * TB, 4L * TB, 365, "warm", "seed"));
            list.add(bucket("archive", 0.3 * TB, 4L * TB, 999, "cold", "seed"));
            list.add(bucket("clickhouse-hot", 1.1 * TB, 2L * TB, 88, "hot", "seed"));
        }

        List<GovLcOrphanScan> scans = orphanScanMapper.selectList(new QueryWrapper<GovLcOrphanScan>().lambda()
                .eq(GovLcOrphanScan::getWs, workspace)
                .eq(GovLcOrphanScan::getDeleteFlag, NOT_DELETE));
        Map<String, Long> orphanByBucket = scans.stream()
                .collect(Collectors.groupingBy(GovLcOrphanScan::getBucket,
                        Collectors.summingLong(s -> s.getBytes() == null ? 0L : s.getBytes())));
        for (Map<String, Object> b : list) {
            String name = String.valueOf(b.get("bucket"));
            if (orphanByBucket.containsKey(name)) {
                b.put("orphanCandidateBytes", orphanByBucket.get(name));
            }
        }
        return list;
    }

    @Override
    public List<Map<String, Object>> advice(String ws) {
        String workspace = wsOrDefault(ws);
        List<GovLcStorageAdvice> rows = adviceMapper.selectList(new QueryWrapper<GovLcStorageAdvice>().lambda()
                .eq(GovLcStorageAdvice::getWs, workspace)
                .eq(GovLcStorageAdvice::getDeleteFlag, NOT_DELETE)
                .in(GovLcStorageAdvice::getStatus, List.of("open", "linked"))
                .orderByAsc(GovLcStorageAdvice::getPriority)
                .orderByDesc(GovLcStorageAdvice::getEstReclaimBytes));
        return rows.stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("dt", a.getDt());
            m.put("fqtn", a.getFqtn());
            m.put("kind", a.getKind());
            m.put("priority", a.getPriority());
            m.put("priorityLabel", priorityLabel(a.getPriority()));
            m.put("estReclaimBytes", a.getEstReclaimBytes());
            m.put("confidence", a.getConfidence());
            m.put("reason", a.getReason());
            m.put("status", a.getStatus());
            m.put("linkedRunId", a.getLinkedRunId());
            m.put("deepLink", Map.of(
                    "path", "/lifecycle",
                    "query", Map.of(
                            "table", StrUtil.blankToDefault(a.getFqtn(), ""),
                            "action", StrUtil.blankToDefault(a.getKind(), ""),
                            "from", "storage-trend",
                            "adviceId", a.getId()
                    )
            ));
            return m;
        }).toList();
    }

    @Override
    public Map<String, Object> showback(String ws, String range, String group) {
        String workspace = wsOrDefault(ws);
        int days = parseRangeDays(range);
        List<TableCaliber> rows = buildCalibers(workspace, days);
        long active = rows.stream().mapToLong(TableCaliber::activeBytes).sum();
        long total = rows.stream().mapToLong(TableCaliber::totalBytes).sum();
        long net = rows.stream().mapToLong(TableCaliber::netGrowthBytes).sum();

        // P0：单空间 stub；正式接 workspace 配额 API
        long quotaBytes = 20L * TB;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ws", workspace);
        row.put("activeBytes", active);
        row.put("totalBytes", total);
        row.put("netGrowthBytes", net);
        row.put("quotaBytes", quotaBytes);
        row.put("quotaPct", pct(total, quotaBytes));
        row.put("owner", "platform");
        row.put("status", total * 100.0 / quotaBytes >= 80 ? "QUOTA_WARN" : "ok");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ws", workspace);
        out.put("range", rangeLabel(days));
        out.put("group", StrUtil.blankToDefault(group, "ws"));
        out.put("list", List.of(row));
        out.put("note", "P0 stub；配额正式读 /lh/workspace/spaces/{id}/quota");
        return out;
    }

    // ─── helpers ───────────────────────────────────────────────

    private List<TableCaliber> buildCalibers(String ws, int days) {
        List<GovLcTableStat> stats = tableStatMapper.selectList(new QueryWrapper<GovLcTableStat>().lambda()
                .eq(GovLcTableStat::getWs, ws)
                .eq(GovLcTableStat::getDeleteFlag, NOT_DELETE));
        Map<String, GovLcPolicy> policies = policyMapper.selectList(new QueryWrapper<GovLcPolicy>().lambda()
                        .eq(GovLcPolicy::getWs, ws)
                        .eq(GovLcPolicy::getDeleteFlag, NOT_DELETE))
                .stream()
                .collect(Collectors.toMap(GovLcPolicy::getTableFqn, p -> p, (a, b) -> a));

        List<TableCaliber> out = new ArrayList<>();
        for (GovLcTableStat s : stats) {
            out.add(derive(s, policies.get(s.getTableFqn()), days));
        }
        return out;
    }

    /**
     * P0 三口径派生：size_bytes 视为活跃量；可回收按小文件/快照/告警启发式估。
     * 正式由 Iceberg 元数据 → VM 的 active/total/reclaimable 取代。
     */
    private TableCaliber derive(GovLcTableStat s, GovLcPolicy policy, int days) {
        long avgFile = nvl(s.getAvgFileBytes());
        long files = nvl(s.getFileCount());
        double growth7 = s.getGrowth7dPct() == null ? 0 : s.getGrowth7dPct().doubleValue();
        double growthWindow = growth7 * (days / 7.0);
        boolean profiled = "ok".equalsIgnoreCase(s.getCollectStatus())
                || "partial".equalsIgnoreCase(s.getCollectStatus());
        long active;
        long reclaimable;
        double smallRatio;
        if (profiled && s.getActiveBytes() != null) {
            active = s.getActiveBytes();
            reclaimable = nvl(s.getReclaimableBytes());
            smallRatio = s.getSmallFileRatio() == null ? 0 : s.getSmallFileRatio().doubleValue();
        } else {
            active = nvl(s.getSizeBytes());
            smallRatio = 0;
            if (avgFile > 0 && avgFile < 32L * 1024 * 1024) {
                smallRatio = Math.min(0.95, 32.0 * 1024 * 1024 / avgFile * 0.15);
            }
            if (files > 200) {
                smallRatio = Math.max(smallRatio, Math.min(0.9, files / 1500.0));
            }
            reclaimable = 0;
            if (smallRatio > 0.3) {
                reclaimable += Math.round(active * 0.08);
            }
            if (policy != null && policy.getKeepDays() != null && policy.getKeepDays() <= 3) {
                reclaimable += Math.round(active * 0.07);
            } else if (s.getSnapshotCount() != null && s.getSnapshotCount() > 15) {
                reclaimable += Math.round(active * 0.05);
            }
            if ("warn".equalsIgnoreCase(StrUtil.blankToDefault(s.getStatus(), "ok"))) {
                reclaimable = Math.max(reclaimable, Math.round(active * 0.12));
            }
            if (reclaimable == 0) {
                reclaimable = Math.round(active * 0.05);
            }
        }

        String attr = "business_growth";
        if (smallRatio > 0.3) {
            attr = "small_file";
        } else if (policy != null && policy.getKeepDays() != null && policy.getKeepDays() <= 3) {
            attr = "snapshot_bloat";
        } else if (s.getSnapshotCount() != null && s.getSnapshotCount() > 15) {
            attr = "snapshot_bloat";
        } else if (reclaimable > 0 && active > 0 && reclaimable * 1.0 / Math.max(1, active) > 0.15) {
            attr = "snapshot_bloat";
        }

        long total = active + reclaimable;
        long netGrowth = Math.round(active * (growthWindow / 100.0));
        boolean anomaly = (growthWindow > 20 && netGrowth >= 10 * GB)
                || (total > 0 && reclaimable * 100.0 / total > 40)
                || smallRatio > 0.30
                || (growthWindow > 5 && netGrowth >= 10 * GB && "warn".equalsIgnoreCase(s.getStatus()));

        String advice = "catalog";
        if ("small_file".equals(attr)) {
            advice = "compact";
        } else if ("snapshot_bloat".equals(attr)) {
            advice = "expire";
        }

        int partitions = Math.max(1, (int) Math.min(files / 8, 500));
        return new TableCaliber(
                s.getTableFqn(),
                s.getLayer(),
                active,
                total,
                reclaimable,
                growthWindow,
                netGrowth,
                smallRatio,
                files,
                avgFile,
                s.getSnapshotCount(),
                partitions,
                s.getPolicyLabel(),
                s.getStatus(),
                s.getCollectedAt(),
                anomaly,
                attr,
                advice,
                policy,
                profiled
        );
    }

    private Map<String, Object> caliberToTableRow(TableCaliber r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fqtn", r.fqtn());
        m.put("tableFqn", r.fqtn());
        m.put("layer", r.layer());
        m.put("activeBytes", r.activeBytes());
        m.put("totalBytes", r.totalBytes());
        m.put("reclaimableBytes", r.reclaimableBytes());
        m.put("growthPct", round2(r.growthPct()));
        m.put("netGrowthBytes", r.netGrowthBytes());
        m.put("smallFileRatio", round2(r.smallFileRatio() * 100));
        m.put("fileCount", r.fileCount());
        m.put("avgFileBytes", r.avgFileBytes());
        m.put("snapshotCount", r.snapshotCount());
        m.put("partitionCount", r.partitionCount());
        m.put("policyLabel", r.policyLabel());
        m.put("status", r.status());
        m.put("anomaly", r.anomaly());
        m.put("attribution", r.attribution());
        m.put("suggestedAction", r.suggestedAction());
        m.put("collectedAt", r.collectedAt());
        m.put("deepLink", Map.of(
                "lifecycle", "/lifecycle?table=" + r.fqtn() + "&action=" + r.suggestedAction() + "&from=storage-trend",
                "catalog", "/catalog?q=" + r.fqtn()
        ));
        return m;
    }

    private Comparator<TableCaliber> comparator(String sortKey) {
        return switch (sortKey) {
            case "activeBytes" -> Comparator.comparingLong(TableCaliber::activeBytes);
            case "totalBytes" -> Comparator.comparingLong(TableCaliber::totalBytes);
            case "growthPct" -> Comparator.comparingDouble(TableCaliber::growthPct);
            case "netGrowthBytes" -> Comparator.comparingLong(TableCaliber::netGrowthBytes);
            case "smallFileRatio" -> Comparator.comparingDouble(TableCaliber::smallFileRatio);
            default -> Comparator.comparingLong(TableCaliber::reclaimableBytes);
        };
    }

    private List<GovLcStorageChangePoint> listChangePoints(String ws, int days) {
        LocalDate from = LocalDate.now().minusDays(days);
        Date fromDate = Date.from(from.atStartOfDay(ZoneId.systemDefault()).toInstant());
        return changePointMapper.selectList(new QueryWrapper<GovLcStorageChangePoint>().lambda()
                .eq(GovLcStorageChangePoint::getWs, ws)
                .eq(GovLcStorageChangePoint::getDeleteFlag, NOT_DELETE)
                .ge(GovLcStorageChangePoint::getDt, fromDate)
                .orderByAsc(GovLcStorageChangePoint::getDt));
    }

    private Map<String, Object> changePointToMap(GovLcStorageChangePoint cp) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", cp.getId());
        m.put("dt", cp.getDt());
        m.put("scopeType", cp.getScopeType());
        m.put("scopeKey", cp.getScopeKey());
        m.put("kind", cp.getKind());
        m.put("note", cp.getNote());
        m.put("sourceRef", cp.getSourceRef());
        return m;
    }

    private Map<String, Object> bucketFromVm(GovLcBucketMetricsReader.BucketSnapshot snap, long nowSec) {
        long usedBytes = Math.max(0L, snap.usedBytes());
        long cap = snap.capacityBytes() != null && snap.capacityBytes() > 0
                ? snap.capacityBytes()
                : Math.max(usedBytes, 1L);
        int lagMin = 0;
        if (snap.scrapedAtSec() != null && snap.scrapedAtSec() > 0) {
            lagMin = (int) Math.max(0, (nowSec - snap.scrapedAtSec()) / 60L);
        }
        // 无历史斜率时仅给水位；TTF 留给有日序列后的 C4/夜莺。粗估：剩余 / (used*0.5%/day)
        Integer ttfP95 = null;
        Integer ttfP50 = null;
        if (cap > usedBytes && usedBytes > 0) {
            double daily = usedBytes * 0.005;
            if (daily > 0) {
                ttfP95 = (int) Math.round((cap - usedBytes) / daily);
                ttfP50 = (int) Math.round(ttfP95 * 1.25);
            }
        }
        String tier = guessTier(snap.bucket());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bucket", snap.bucket());
        m.put("tier", tier);
        m.put("usedBytes", usedBytes);
        m.put("capacityBytes", cap);
        m.put("usagePct", pct(usedBytes, cap));
        m.put("objectCount", snap.objectCount());
        m.put("daysToFullP50", ttfP50);
        m.put("daysToFullP95", ttfP95);
        m.put("scrapeLagMinutes", lagMin);
        m.put("alert", ttfP95 != null && ttfP95 < 45 ? "danger"
                : (ttfP95 != null && ttfP95 < 90 ? "warn" : "ok"));
        m.put("source", "vm:" + StrUtil.blankToDefault(snap.source(), "bucket"));
        return m;
    }

    private static String guessTier(String bucket) {
        if (bucket == null) {
            return "warm";
        }
        String b = bucket.toLowerCase(Locale.ROOT);
        if (b.contains("hot") || b.contains("clickhouse")) {
            return "hot";
        }
        if (b.contains("archive") || b.contains("cold")) {
            return "cold";
        }
        return "warm";
    }

    private Map<String, Object> bucket(String name, double used, long cap, int ttfP95, String tier, String source) {
        long usedBytes = Math.round(used);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bucket", name);
        m.put("tier", tier);
        m.put("usedBytes", usedBytes);
        m.put("capacityBytes", cap);
        m.put("usagePct", pct(usedBytes, cap));
        m.put("daysToFullP50", Math.round(ttfP95 * 1.25));
        m.put("daysToFullP95", ttfP95);
        m.put("alert", ttfP95 < 45 ? "danger" : (ttfP95 < 90 ? "warn" : "ok"));
        m.put("source", source);
        return m;
    }

    private double growthForLayer(List<TableCaliber> rows, String layer) {
        return rows.stream().filter(r -> layer.equalsIgnoreCase(r.layer()))
                .mapToDouble(TableCaliber::growthPct).average().orElse(0);
    }

    private long netForLayer(List<TableCaliber> rows, String layer) {
        return rows.stream().filter(r -> layer.equalsIgnoreCase(r.layer()))
                .mapToLong(TableCaliber::netGrowthBytes).sum();
    }

    private static int parseRangeDays(String range) {
        if (StrUtil.isBlank(range)) {
            return 30;
        }
        String r = range.trim().toLowerCase(Locale.ROOT);
        if (r.startsWith("7")) {
            return 7;
        }
        if (r.startsWith("90")) {
            return 90;
        }
        if (r.startsWith("30")) {
            return 30;
        }
        try {
            int n = Integer.parseInt(r.replaceAll("[^0-9]", ""));
            if (n <= 7) {
                return 7;
            }
            if (n <= 30) {
                return 30;
            }
            return 90;
        } catch (Exception e) {
            return 30;
        }
    }

    private static String rangeLabel(int days) {
        return days + "d";
    }

    private static String wsOrDefault(String ws) {
        return StrUtil.blankToDefault(ws, WS_DEFAULT);
    }

    private static long nvl(Long v) {
        return v == null ? 0L : v;
    }

    private static BigDecimal pct(long part, long whole) {
        if (whole <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(part * 100.0 / whole).setScale(1, RoundingMode.HALF_UP);
    }

    private static BigDecimal round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static String priorityLabel(Integer p) {
        if (p == null) {
            return "P2";
        }
        return switch (p) {
            case 1 -> "P1";
            case 3 -> "P3";
            default -> "P2";
        };
    }

    private record TableCaliber(
            String fqtn,
            String layer,
            long activeBytes,
            long totalBytes,
            long reclaimableBytes,
            double growthPct,
            long netGrowthBytes,
            double smallFileRatio,
            long fileCount,
            long avgFileBytes,
            Integer snapshotCount,
            int partitionCount,
            String policyLabel,
            String status,
            Date collectedAt,
            boolean anomaly,
            String attribution,
            String suggestedAction,
            GovLcPolicy policy,
            boolean profiled
    ) {
    }
}
