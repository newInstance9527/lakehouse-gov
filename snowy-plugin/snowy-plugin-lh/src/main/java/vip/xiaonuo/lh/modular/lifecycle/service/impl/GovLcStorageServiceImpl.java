package vip.xiaonuo.lh.modular.lifecycle.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcOrphanScan;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcPolicy;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcRun;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcStorageAdvice;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcStorageChangePoint;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcTableStat;
import vip.xiaonuo.lh.modular.lifecycle.mapper.GovLcOrphanScanMapper;
import vip.xiaonuo.lh.modular.lifecycle.mapper.GovLcPolicyMapper;
import vip.xiaonuo.lh.modular.lifecycle.mapper.GovLcRunMapper;
import vip.xiaonuo.lh.modular.lifecycle.mapper.GovLcStorageAdviceMapper;
import vip.xiaonuo.lh.modular.lifecycle.mapper.GovLcStorageChangePointMapper;
import vip.xiaonuo.lh.modular.lifecycle.mapper.GovLcTableStatMapper;
import vip.xiaonuo.lh.modular.lifecycle.service.GovLcStorageService;
import vip.xiaonuo.lh.modular.lifecycle.support.GovLcBucketMetricsReader;
import vip.xiaonuo.lh.modular.lifecycle.support.GovLcMetadataSql;
import vip.xiaonuo.lh.modular.lifecycle.support.GovLcStorageAssetEnricher;
import vip.xiaonuo.lh.modular.lifecycle.support.GovLcStorageCaliberMath;
import vip.xiaonuo.lh.modular.lifecycle.support.GovLcTableMetricsReader;
import vip.xiaonuo.lh.core.engine.TrinoClient;
import vip.xiaonuo.lh.modular.observability.support.LhFinOpsRates;
import vip.xiaonuo.lh.modular.workspace.entity.GovWs;
import vip.xiaonuo.lh.modular.workspace.entity.GovWsQuota;
import vip.xiaonuo.lh.modular.workspace.mapper.GovWsMapper;
import vip.xiaonuo.lh.modular.workspace.mapper.GovWsQuotaMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 存储趋势：三口径优先读画像 {@code gov_lc_table_stat}（日批真值）；时序/预测读 VM {@code lh_table_storage_*}。
 * 无数据时空态；禁止种子桶 / 合成日曲线 / 启发式可回收。
 */
@Service
public class GovLcStorageServiceImpl implements GovLcStorageService {

    private static final String WS_DEFAULT = "default";
    private static final String NOT_DELETE = "NOT_DELETE";
    private static final long GB = LhFinOpsRates.GB;
    private static final long TB = LhFinOpsRates.TB;
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("MM-dd");

    @Resource
    private LhProperties lhProperties;
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
    @Resource
    private GovLcTableMetricsReader tableMetricsReader;
    @Resource
    private GovLcStorageAssetEnricher assetEnricher;
    @Resource
    private GovLcRunMapper runMapper;
    @Resource
    private TrinoClient trinoClient;
    @Resource
    private GovWsMapper govWsMapper;
    @Resource
    private GovWsQuotaMapper govWsQuotaMapper;

    @Override
    public Map<String, Object> summary(String ws, String range) {
        String filterWs = normalizeFilterWs(ws);
        int days = parseRangeDays(range);
        List<TableCaliber> rows = buildCalibers(filterWs, days);

        long active = rows.stream().mapToLong(TableCaliber::activeBytes).sum();
        long reclaimable = rows.stream().mapToLong(TableCaliber::reclaimableBytes).sum();
        long total = active + reclaimable;
        long netGrowth = rows.stream().mapToLong(TableCaliber::netGrowthBytes).sum();

        // 桶为基础设施维度，不随空间软过滤收窄
        List<Map<String, Object>> bucketList = bucketRows(null);
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
        out.put("ws", filterWs);
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
                ? (tableMetricsReader.available()
                ? "gov_lc_table_stat(profile)+lh_table_storage_*; buckets=" + (bucketMetricsReader.available() ? "vm" : "empty")
                : "gov_lc_table_stat(profile); VM 未配")
                : "empty");
        out.put("caliberNote", "active+reclaimable=physical；无画像/无 VM 时 KPI 为 0");
        out.put("bucketSource", bucketMetricsReader.available() ? "vm" : "empty");
        GovLcStorageCaliberMath.assertHolds(out);
        return out;
    }

    @Override
    public Map<String, Object> trend(String ws, String range, String group) {
        String filterWs = normalizeFilterWs(ws);
        int days = parseRangeDays(range);
        String grp = StrUtil.blankToDefault(group, "layer").toLowerCase(Locale.ROOT);
        List<TableCaliber> rows = buildCalibers(filterWs, days);

        long activeNow = rows.stream().mapToLong(TableCaliber::activeBytes).sum();
        long reclaimNow = rows.stream().mapToLong(TableCaliber::reclaimableBytes).sum();
        long totalNow = activeNow + reclaimNow;

        List<Map<String, Object>> daily = new ArrayList<>();
        List<GovLcTableMetricsReader.DailyCaliber> vmDaily = tableMetricsReader.dailyAggregate(filterWs, days);
        if (!vmDaily.isEmpty()) {
            for (GovLcTableMetricsReader.DailyCaliber d : vmDaily) {
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("day", d.date().format(DAY_FMT));
                point.put("date", d.date().toString());
                point.put("totalBytes", d.totalBytes());
                point.put("activeBytes", d.activeBytes());
                point.put("reclaimableBytes", d.reclaimableBytes());
                daily.add(point);
            }
        }
        // 无 VM 日序列：不合成假曲线；仅当有当日画像时给单点
        if (daily.isEmpty() && totalNow > 0) {
            LocalDate today = LocalDate.now();
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("day", today.format(DAY_FMT));
            point.put("date", today.toString());
            point.put("totalBytes", totalNow);
            point.put("activeBytes", activeNow);
            point.put("reclaimableBytes", reclaimNow);
            daily.add(point);
        }

        List<Map<String, Object>> series;
        if ("bucket".equals(grp)) {
            series = bucketRows(null).stream().map(b -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", b.get("bucket"));
                m.put("totalBytes", b.get("usedBytes"));
                m.put("label", b.get("bucket"));
                return m;
            }).toList();
        } else if ("ws".equals(grp)) {
            Map<String, long[]> byWs = new LinkedHashMap<>();
            for (TableCaliber r : rows) {
                String code = StrUtil.blankToDefault(r.ws(), WS_DEFAULT);
                byWs.computeIfAbsent(code, k -> new long[3]);
                long[] a = byWs.get(code);
                a[0] += r.activeBytes();
                a[1] += r.totalBytes();
                a[2] += r.reclaimableBytes();
            }
            if (byWs.isEmpty() && StrUtil.isNotBlank(filterWs)) {
                byWs.put(filterWs, new long[]{activeNow, totalNow, reclaimNow});
            }
            series = byWs.entrySet().stream().map(e -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", e.getKey());
                m.put("label", e.getKey());
                m.put("ws", e.getKey());
                m.put("activeBytes", e.getValue()[0]);
                m.put("totalBytes", e.getValue()[1]);
                m.put("reclaimableBytes", e.getValue()[2]);
                return m;
            }).toList();
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

        List<Map<String, Object>> changePoints = listChangePoints(filterWs, days).stream()
                .map(this::changePointToMap)
                .toList();

        Map<String, Object> forecast = new LinkedHashMap<>();
        GovLcTableMetricsReader.ForecastSnap snap = tableMetricsReader.forecast(filterWs);
        if (days < 15) {
            forecast.put("available", false);
            forecast.put("reason", "INSUFFICIENT");
            forecast.put("note", "窗口不足 15 天，不出预测");
        } else if (snap.p50DaysToFull() == null && snap.p95DaysToFull() == null) {
            forecast.put("available", false);
            forecast.put("reason", snap.sampleSeries() <= 0 ? "NO_SERIES" : "INSUFFICIENT");
            forecast.put("note", tableMetricsReader.available()
                    ? "VM 无 days_to_full 点（需日批样本≥15 天）"
                    : "未配置 lh.lifecycle.vm-import-url");
            forecast.put("source", snap.source());
        } else {
            forecast.put("available", true);
            forecast.put("p50DaysToFull", snap.p50DaysToFull() == null ? null : Math.round(snap.p50DaysToFull()));
            forecast.put("p95DaysToFull", snap.p95DaysToFull() == null ? null : Math.round(snap.p95DaysToFull()));
            forecast.put("sampleSeries", snap.sampleSeries());
            forecast.put("source", snap.source());
            forecast.put("note", "读 lh_table_storage_days_to_full（日批派生）");
            long capacity = lhProperties.getLifecycle() != null
                    ? lhProperties.getLifecycle().getForecastDefaultCapacityBytes()
                    : 20L * TB;
            forecast.put("capacityBytes", capacity);
            Double p50 = snap.p50DaysToFull();
            Double p95 = snap.p95DaysToFull();
            List<Map<String, Object>> band = new ArrayList<>();
            LocalDate today = LocalDate.now();
            int horizon = Math.min(120, Math.max(7, (int) Math.ceil(Math.max(
                    p50 != null && p50 > 0 ? p50 : 30,
                    p95 != null && p95 > 0 ? p95 : 30))));
            for (int i = 0; i <= horizon; i++) {
                LocalDate d = today.plusDays(i);
                Map<String, Object> pt = new LinkedHashMap<>();
                pt.put("date", d.toString());
                pt.put("day", d.format(DAY_FMT));
                if (p50 != null && p50 > 0) {
                    pt.put("p50Bytes", Math.min(capacity,
                            Math.round(totalNow + (capacity - totalNow) * (i / p50))));
                }
                if (p95 != null && p95 > 0) {
                    pt.put("p95Bytes", Math.min(capacity,
                            Math.round(totalNow + (capacity - totalNow) * (i / p95))));
                }
                band.add(pt);
            }
            forecast.put("band", band);
            if (p95 != null && p95 > 0 && totalNow < capacity) {
                forecast.put("capacityIntersectDate", today.plusDays(Math.round(p95)).toString());
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ws", filterWs);
        out.put("range", rangeLabel(days));
        out.put("rangeDays", days);
        out.put("group", grp);
        out.put("daily", daily);
        out.put("series", series);
        out.put("changePoints", changePoints);
        out.put("forecast", forecast);
        out.put("source", "bucket".equals(grp)
                ? (bucketMetricsReader.available() ? "lh_bucket_storage_* via VM" : "empty")
                : (tableMetricsReader.available() && !vmDaily.isEmpty()
                ? "lh_table_storage_* via VM"
                : (totalNow > 0 ? "gov_lc_table_stat(profile) snapshot-only" : "empty")));
        return out;
    }

    @Override
    public Map<String, Object> tables(String ws, String range, String layer, String filter,
                                      String sort, String order, Integer page, Integer size) {
        String filterWs = normalizeFilterWs(ws);
        int days = parseRangeDays(range);
        List<TableCaliber> rows = buildCalibers(filterWs, days);

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
        out.put("ws", filterWs);
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
        List<GovLcTableMetricsReader.DailyCaliber> vmCurve = tableMetricsReader.dailyForTable(workspace, fqn, days);
        if (!vmCurve.isEmpty()) {
            for (GovLcTableMetricsReader.DailyCaliber d : vmCurve) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("date", d.date().toString());
                p.put("activeBytes", d.activeBytes());
                p.put("totalBytes", d.totalBytes());
                p.put("reclaimableBytes", d.reclaimableBytes());
                curve.add(p);
            }
        } else {
            // 无 VM 历史：只给当日画像点，不合成斜线
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("date", LocalDate.now().toString());
            p.put("activeBytes", hit.activeBytes());
            p.put("totalBytes", hit.totalBytes());
            p.put("reclaimableBytes", hit.reclaimableBytes());
            curve.add(p);
        }

        GovLcPolicy policy = policyMapper.selectOne(new QueryWrapper<GovLcPolicy>().lambda()
                .eq(GovLcPolicy::getWs, workspace)
                .eq(GovLcPolicy::getTableFqn, fqn)
                .eq(GovLcPolicy::getDeleteFlag, NOT_DELETE)
                .last("LIMIT 1"));

        Map<String, Object> partitionHint = queryPartitionsLive(fqn, hit.partitionCount());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ws", workspace);
        out.put("fqtn", fqn);
        out.put("range", rangeLabel(days));
        out.put("row", caliberToTableRow(hit));
        out.put("curve", curve);
        out.put("curveSource", vmCurve.isEmpty() ? "profile-snapshot" : "vm");
        out.put("partitionHint", partitionHint);
        out.put("policy", policy == null ? null : Map.of(
                "keepCount", policy.getKeepCount(),
                "keepDays", policy.getKeepDays(),
                "compactLevel", policy.getCompactLevel(),
                "targetFileMb", policy.getTargetFileMb()
        ));
        out.put("deepLink", Map.of(
                "lifecycle", "/lifecycle?table=" + fqn + "&from=storage-trend",
                "catalog", "/catalog?q=" + fqn,
                "querygov", "/querygov?ws=" + workspace + "&range=" + rangeLabel(days)
        ));
        // 最近三次生命周期作业
        List<GovLcRun> runs = runMapper.selectList(new QueryWrapper<GovLcRun>().lambda()
                .eq(GovLcRun::getDeleteFlag, NOT_DELETE)
                .eq(GovLcRun::getTableFqn, fqn)
                .orderByDesc(GovLcRun::getStartedAt)
                .last("LIMIT 3"));
        List<Map<String, Object>> recentRuns = new ArrayList<>();
        for (GovLcRun run : runs) {
            Map<String, Object> rr = new LinkedHashMap<>();
            rr.put("runId", run.getId());
            rr.put("kind", run.getKind());
            rr.put("status", run.getStatus());
            rr.put("startedAt", run.getStartedAt());
            rr.put("finishedAt", run.getFinishedAt());
            recentRuns.add(rr);
        }
        out.put("recentRuns", recentRuns);
        Integer snapCount = hit.snapshotCount();
        out.put("snapshotAge", Map.of(
                "snapshotCount", snapCount == null ? 0 : snapCount,
                "note", snapCount == null || snapCount <= 0
                        ? "无快照计数"
                        : "快照数 " + snapCount + "（年龄明细需 $snapshots 扩展）"
        ));
        GovLcStorageAssetEnricher.AssetMeta meta = assetEnricher.resolve(fqn);
        if (meta != null) {
            out.put("owner", meta.owner());
            if (StrUtil.isNotBlank(meta.layer())) {
                @SuppressWarnings("unchecked")
                Map<String, Object> row = (Map<String, Object>) out.get("row");
                if (row != null) {
                    row.put("layer", meta.layer());
                    row.put("layerSource", "gov_asset");
                    row.put("owner", meta.owner());
                }
            }
        }
        return out;
    }

    @Override
    public Map<String, Object> buckets(String ws) {
        String workspace = wsOrDefault(ws);
        List<Map<String, Object>> list = new ArrayList<>();
        boolean vmConfigured = bucketMetricsReader.available();
        List<GovLcBucketMetricsReader.BucketSnapshot> fromVm = bucketMetricsReader.listBuckets();
        String family = null;
        if (!fromVm.isEmpty()) {
            long nowSec = System.currentTimeMillis() / 1000L;
            for (GovLcBucketMetricsReader.BucketSnapshot snap : fromVm) {
                list.add(bucketFromVm(snap, nowSec));
                if (family == null && StrUtil.isNotBlank(snap.source())) {
                    family = snap.source();
                }
            }
        }
        // 无 VM / 无点：空列表合法，禁止演示桶回落

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

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ws", workspace);
        out.put("vmConfigured", vmConfigured);
        // 验收契约：Categraf→VM 有点时顶层 source 必须以 vm: 开头
        if (!list.isEmpty()) {
            out.put("source", "vm:" + StrUtil.blankToDefault(family, "lh_bucket_storage_*"));
        } else if (vmConfigured) {
            out.put("source", "vm:empty");
            out.put("hint", "VM 已配但无桶 series；确认 Categraf MinIO 已写入 lh_bucket_storage_* / minio_bucket_*");
        } else {
            out.put("source", "unconfigured");
            out.put("hint", "未配置 lh.lifecycle.vm-import-url");
        }
        out.put("list", list);
        out.put("count", list.size());
        return out;
    }

    /** 从 {@link #buckets(String)} 信封取出 list，兼容调用方按行遍历。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> bucketRows(String ws) {
        Map<String, Object> payload = buckets(ws);
        Object list = payload == null ? null : payload.get("list");
        if (list instanceof List<?> raw) {
            return (List<Map<String, Object>>) raw;
        }
        return List.of();
    }

    @Override
    public List<Map<String, Object>> advice(String ws) {
        String filterWs = normalizeFilterWs(ws);
        var qw = new QueryWrapper<GovLcStorageAdvice>().lambda()
                .eq(GovLcStorageAdvice::getDeleteFlag, NOT_DELETE)
                .in(GovLcStorageAdvice::getStatus, List.of("open", "linked"))
                .orderByAsc(GovLcStorageAdvice::getPriority)
                .orderByDesc(GovLcStorageAdvice::getEstReclaimBytes);
        if (StrUtil.isNotBlank(filterWs)) {
            qw.eq(GovLcStorageAdvice::getWs, filterWs);
        }
        List<GovLcStorageAdvice> rows = adviceMapper.selectList(qw);
        return rows.stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("dt", a.getDt());
            m.put("ws", a.getWs());
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
        int days = parseRangeDays(range);
        String g = StrUtil.blankToDefault(group, "ws").trim().toLowerCase(Locale.ROOT);
        if ("workspace".equals(g) || "workspace_id".equals(g)) {
            g = "ws";
        }
        String filterWs = StrUtil.trim(ws);
        boolean allWs = "ws".equals(g) && StrUtil.isBlank(filterWs);

        Map<String, String> ownerByWs = new LinkedHashMap<>();
        Map<String, Long> quotaByWs = new LinkedHashMap<>();
        List<GovWs> spaces = govWsMapper.selectList(new QueryWrapper<GovWs>().lambda()
                .eq(GovWs::getDeleteFlag, NOT_DELETE)
                .eq(GovWs::getStatus, "active"));
        for (GovWs w : spaces) {
            String code = StrUtil.blankToDefault(w.getWsCode(), WS_DEFAULT);
            ownerByWs.put(code, StrUtil.blankToDefault(w.getOwners(), "—"));
        }
        List<GovWsQuota> quotas = govWsQuotaMapper.selectList(new QueryWrapper<GovWsQuota>().lambda()
                .eq(GovWsQuota::getDeleteFlag, NOT_DELETE));
        for (GovWsQuota q : quotas) {
            String code = StrUtil.blankToDefault(q.getWsCode(), WS_DEFAULT);
            if (q.getStorageQuotaTb() != null) {
                quotaByWs.put(code, q.getStorageQuotaTb().multiply(BigDecimal.valueOf(TB)).longValue());
            }
        }

        Set<String> codes = new LinkedHashSet<>();
        if (allWs) {
            codes.addAll(ownerByWs.keySet());
            List<GovLcTableStat> allStats = tableStatMapper.selectList(new QueryWrapper<GovLcTableStat>().lambda()
                    .eq(GovLcTableStat::getDeleteFlag, NOT_DELETE));
            for (GovLcTableStat s : allStats) {
                codes.add(StrUtil.blankToDefault(s.getWs(), WS_DEFAULT));
            }
            if (codes.isEmpty()) {
                codes.add(WS_DEFAULT);
            }
        } else {
            codes.add(wsOrDefault(filterWs));
        }

        List<Map<String, Object>> list = new ArrayList<>();
        BigDecimal totalCostAll = BigDecimal.ZERO;
        BigDecimal perTb = LhFinOpsRates.storagePerTbMonth(lhProperties);
        for (String code : codes) {
            List<TableCaliber> rows = buildCalibers(code, days);
            long active = rows.stream().mapToLong(TableCaliber::activeBytes).sum();
            long total = rows.stream().mapToLong(TableCaliber::totalBytes).sum();
            long net = rows.stream().mapToLong(TableCaliber::netGrowthBytes).sum();
            long quotaBytes = quotaByWs.getOrDefault(code, 0L);
            BigDecimal storageCost = LhFinOpsRates.storageCost(total, days, perTb);
            totalCostAll = totalCostAll.add(storageCost);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ws", code);
            row.put("activeBytes", active);
            row.put("totalBytes", total);
            row.put("netGrowthBytes", net);
            row.put("quotaBytes", quotaBytes);
            row.put("quotaPct", quotaBytes > 0 ? pct(total, quotaBytes) : null);
            row.put("storageCost", storageCost);
            row.put("storageCostLabel", LhFinOpsRates.formatCny(storageCost));
            row.put("owner", ownerByWs.getOrDefault(code, "—"));
            row.put("status", quotaBytes > 0 && total * 100.0 / quotaBytes >= 80 ? "QUOTA_WARN" : "ok");
            list.add(row);
        }
        list.sort(Comparator.comparingLong((Map<String, Object> m) -> (Long) m.get("totalBytes")).reversed());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ws", allWs ? null : wsOrDefault(filterWs));
        out.put("range", rangeLabel(days));
        out.put("group", g);
        out.put("currency", LhFinOpsRates.currency(lhProperties));
        out.put("rates", LhFinOpsRates.ratesMap(lhProperties));
        out.put("list", list);
        out.put("totalStorageCost", totalCostAll.setScale(2, RoundingMode.HALF_UP));
        out.put("note", "配额读 gov_ws_quota；金额引 lh.finops（§24.3）与 /lh/observability/costs 同源");
        return out;
    }

    // ─── helpers ───────────────────────────────────────────────

    private List<TableCaliber> buildCalibers(String ws, int days) {
        // 软过滤：空 ws = 查看全部；有值则按归属筛（与目录/指标一致）
        var qw = new QueryWrapper<GovLcTableStat>().lambda()
                .eq(GovLcTableStat::getDeleteFlag, NOT_DELETE);
        if (StrUtil.isNotBlank(ws)) {
            qw.eq(GovLcTableStat::getWs, ws);
        }
        List<GovLcTableStat> stats = tableStatMapper.selectList(qw);

        var polQw = new QueryWrapper<GovLcPolicy>().lambda()
                .eq(GovLcPolicy::getDeleteFlag, NOT_DELETE);
        if (StrUtil.isNotBlank(ws)) {
            polQw.eq(GovLcPolicy::getWs, ws);
        }
        Map<String, GovLcPolicy> policies = policyMapper.selectList(polQw)
                .stream()
                .collect(Collectors.toMap(
                        p -> StrUtil.blankToDefault(p.getWs(), WS_DEFAULT) + "\0" + p.getTableFqn(),
                        p -> p,
                        (a, b) -> a));

        List<TableCaliber> out = new ArrayList<>();
        for (GovLcTableStat s : stats) {
            String code = StrUtil.blankToDefault(s.getWs(), WS_DEFAULT);
            GovLcPolicy policy = policies.get(code + "\0" + s.getTableFqn());
            out.add(derive(s, policy, days));
        }
        return out;
    }

    /**
     * 三口径：仅用画像日批回写的 active/reclaimable；未画像时 size_bytes 作活跃、可回收=0。
     * 禁止启发式编造可回收/小文件率。
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
        } else if (profiled) {
            active = nvl(s.getSizeBytes());
            reclaimable = nvl(s.getReclaimableBytes());
            smallRatio = s.getSmallFileRatio() == null ? 0 : s.getSmallFileRatio().doubleValue();
        } else {
            // 未画像：不进 Top 假水位；仍可展示 size 若有，可回收一律 0
            active = nvl(s.getSizeBytes());
            reclaimable = 0;
            smallRatio = s.getSmallFileRatio() == null ? 0 : s.getSmallFileRatio().doubleValue();
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
        boolean anomaly = profiled && ((growthWindow > 20 && netGrowth >= 10 * GB)
                || (total > 0 && reclaimable * 100.0 / total > 40)
                || smallRatio > 0.30
                || (growthWindow > 5 && netGrowth >= 10 * GB && "warn".equalsIgnoreCase(s.getStatus())));

        String advice = "catalog";
        if ("small_file".equals(attr)) {
            advice = "compact";
        } else if ("snapshot_bloat".equals(attr)) {
            advice = "expire";
        }

        int partitions = 0;
        return new TableCaliber(
                StrUtil.blankToDefault(s.getWs(), WS_DEFAULT),
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
        m.put("ws", r.ws());
        m.put("fqtn", r.fqtn());
        m.put("tableFqn", r.fqtn());
        String layer = r.layer();
        String owner = null;
        String layerSource = "policy-or-stat";
        GovLcStorageAssetEnricher.AssetMeta meta = assetEnricher.resolve(r.fqtn());
        if (meta != null) {
            if (StrUtil.isNotBlank(meta.layer())) {
                layer = meta.layer();
                layerSource = "gov_asset";
            }
            owner = meta.owner();
            if (StrUtil.isNotBlank(meta.ws()) && StrUtil.isBlank(r.ws())) {
                m.put("ws", meta.ws());
            }
        }
        m.put("layer", layer);
        m.put("layerSource", layerSource);
        m.put("owner", owner);
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
                "catalog", "/catalog?q=" + r.fqtn(),
                "workspace", "/workspace?ws=" + r.ws(),
                "querygov", "/querygov?ws=" + r.ws() + "&range=30d"
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
        var qw = new QueryWrapper<GovLcStorageChangePoint>().lambda()
                .eq(GovLcStorageChangePoint::getDeleteFlag, NOT_DELETE)
                .ge(GovLcStorageChangePoint::getDt, fromDate)
                .orderByAsc(GovLcStorageChangePoint::getDt);
        if (StrUtil.isNotBlank(ws)) {
            qw.eq(GovLcStorageChangePoint::getWs, ws);
        }
        return changePointMapper.selectList(qw);
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

    /** @deprecated 禁止种子调用；保留签名避免误用编译引用 */
    @Deprecated
    @SuppressWarnings("unused")
    private void noSeedBuckets() {
        // intentionally empty
    }

    @Override
    public Map<String, Object> reportExport(String ws, String range, String format) {
        String filterWs = normalizeFilterWs(ws);
        int days = parseRangeDays(range);
        String fmt = StrUtil.blankToDefault(format, "csv").toLowerCase(Locale.ROOT);
        Map<String, Object> page = tables(filterWs, rangeLabel(days), null, "all", "totalBytes", "desc", 1, 500);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) page.getOrDefault("list", List.of());
        Map<String, Object> sum = summary(filterWs, rangeLabel(days));

        StringBuilder sb = new StringBuilder();
        if ("md".equals(fmt) || "markdown".equals(fmt)) {
            sb.append("# 存储日报 ").append(rangeLabel(days)).append("\n\n");
            sb.append("- 物理: ").append(sum.get("physicalBytes")).append("\n");
            sb.append("- 活跃: ").append(sum.get("activeBytes")).append("\n");
            sb.append("- 可回收: ").append(sum.get("reclaimableBytes")).append("\n\n");
            sb.append("| fqtn | layer | total | active | reclaimable | growth% |\n|---|---|---:|---:|---:|---:|\n");
            for (Map<String, Object> r : list) {
                sb.append("| ").append(r.get("fqtn")).append(" | ").append(r.get("layer"))
                        .append(" | ").append(r.get("totalBytes")).append(" | ").append(r.get("activeBytes"))
                        .append(" | ").append(r.get("reclaimableBytes")).append(" | ").append(r.get("growthPct"))
                        .append(" |\n");
            }
        } else {
            sb.append("fqtn,layer,totalBytes,activeBytes,reclaimableBytes,growthPct,fileCount,smallFileRatio\n");
            for (Map<String, Object> r : list) {
                sb.append(csv(r.get("fqtn"))).append(',')
                        .append(csv(r.get("layer"))).append(',')
                        .append(r.get("totalBytes")).append(',')
                        .append(r.get("activeBytes")).append(',')
                        .append(r.get("reclaimableBytes")).append(',')
                        .append(r.get("growthPct")).append(',')
                        .append(r.get("fileCount")).append(',')
                        .append(r.get("smallFileRatio")).append('\n');
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ws", filterWs);
        out.put("range", rangeLabel(days));
        out.put("format", "md".equals(fmt) || "markdown".equals(fmt) ? "md" : "csv");
        out.put("rowCount", list.size());
        out.put("content", sb.toString());
        out.put("filename", "storage-report-" + rangeLabel(days) + ("md".equals(fmt) ? ".md" : ".csv"));
        out.put("note", list.isEmpty() ? "无画像表；空导出合法" : "治理角色可读；内容来自 gov_lc_table_stat / VM");
        return out;
    }

    private Map<String, Object> queryPartitionsLive(String fqn, int fallbackCount) {
        Map<String, Object> hint = new LinkedHashMap<>();
        hint.put("partitionCount", fallbackCount);
        hint.put("source", "none");
        try {
            String catalog = lhProperties.getLifecycle() != null
                    ? StrUtil.blankToDefault(lhProperties.getLifecycle().getSparkCatalog(), "iceberg")
                    : "iceberg";
            if (StrUtil.isBlank(catalog)) {
                catalog = "iceberg";
            }
            GovLcMetadataSql.TableRef ref = GovLcMetadataSql.parse(fqn, catalog);
            String sql = GovLcMetadataSql.partitions(ref);
            TrinoClient.ExecuteOptions opts = TrinoClient.ExecuteOptions.job(5);
            opts.catalog = ref.catalog();
            opts.schema = ref.schema();
            opts.timeoutMs = 15_000;
            opts.source = "job.lifecycle";
            opts.clientTags = "job.lifecycle,storage-detail";
            Map<String, Object> exec = trinoClient.execute(sql, opts);
            if (Boolean.TRUE.equals(exec.get("degraded"))) {
                hint.put("note", String.valueOf(exec.get("message")));
                hint.put("source", "degraded");
                return hint;
            }
            Object rowsObj = exec.get("rows");
            if (rowsObj instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> map) {
                Object n = map.get("partition_count");
                if (n == null && !map.isEmpty()) {
                    n = map.values().iterator().next();
                }
                long count = n instanceof Number ? ((Number) n).longValue() : Long.parseLong(String.valueOf(n));
                hint.put("partitionCount", count);
                hint.put("source", "trino $partitions");
                hint.put("note", "即时读 Iceberg 元数据");
                return hint;
            }
            hint.put("note", "$partitions 无行");
        } catch (Exception e) {
            hint.put("note", "查询 $partitions 失败: " + StrUtil.maxLength(e.getMessage(), 160));
            hint.put("source", "error");
        }
        return hint;
    }

    private static String csv(Object v) {
        String s = v == null ? "" : String.valueOf(v);
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
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

    /** 软过滤：空 = 全部；有值则原样返回（不去默认成 default） */
    private static String normalizeFilterWs(String ws) {
        String t = StrUtil.trim(ws);
        return StrUtil.isBlank(t) ? null : t;
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
            String ws,
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
