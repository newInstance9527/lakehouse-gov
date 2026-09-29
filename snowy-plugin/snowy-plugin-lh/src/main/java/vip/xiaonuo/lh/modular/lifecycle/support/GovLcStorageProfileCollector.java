package vip.xiaonuo.lh.modular.lifecycle.support;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.engine.GravitinoClient;
import vip.xiaonuo.lh.core.engine.TrinoClient;
import vip.xiaonuo.lh.core.engine.VictoriaMetricsClient;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcPolicy;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcRun;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcTableStat;
import vip.xiaonuo.lh.modular.lifecycle.mapper.GovLcPolicyMapper;
import vip.xiaonuo.lh.modular.lifecycle.mapper.GovLcRunMapper;
import vip.xiaonuo.lh.modular.lifecycle.mapper.GovLcTableStatMapper;
import vip.xiaonuo.lh.modular.workspace.entity.GovWs;
import vip.xiaonuo.lh.modular.workspace.entity.GovWsQuota;
import vip.xiaonuo.lh.modular.workspace.mapper.GovWsMapper;
import vip.xiaonuo.lh.modular.workspace.mapper.GovWsQuotaMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * {@code job.storage.profile_daily}：Trino 读 Iceberg {@code $files}/{@code $snapshots}/{@code $partitions}，
 * 回写 {@code gov_lc_table_stat}，并直写 VictoriaMetrics {@code lh_table_storage_*}（禁 Pushgateway）。
 * DS 同名流程只作调度锚点；写库/写 VM 在门户，避免 Spark 作业直连业务库。
 */
@Component
public class GovLcStorageProfileCollector {

    private static final Logger log = LoggerFactory.getLogger(GovLcStorageProfileCollector.class);
    private static final String NOT_DELETE = "NOT_DELETE";
    private static final String WS_DEFAULT = "default";

    @Resource
    private TrinoClient trinoClient;
    @Resource
    private VictoriaMetricsClient victoriaMetricsClient;
    @Resource
    private GravitinoClient gravitinoClient;
    @Resource
    private LhProperties lhProperties;
    @Resource
    private GovLcDsLauncher dsLauncher;
    @Resource
    private GovLcPolicyMapper policyMapper;
    @Resource
    private GovLcTableStatMapper tableStatMapper;
    @Resource
    private GovLcRunMapper runMapper;
    @Resource
    private GovLcStorageAdviceWriter adviceWriter;
    @Resource
    private GovLcStorageDaysToFullDeriver daysToFullDeriver;
    @Resource
    private GovLcStorageAssetEnricher assetEnricher;
    @Resource
    private GovWsQuotaMapper govWsQuotaMapper;
    @Resource
    private GovWsMapper govWsMapper;

    private static final long TB = 1024L * 1024L * 1024L * 1024L;

    /** 日批：画像治理范围内的表，并登记 DS 流程 {@code job.storage.profile_daily}。 */
    public Map<String, Object> runDaily(String ws) {
        return profile(ws, null, true);
    }

    /** 作业成功后的单表/少量表刷新，不另开日批流程。 */
    public Map<String, Object> refresh(String ws, List<String> tableFqns) {
        return profile(ws, tableFqns, false);
    }

    private Map<String, Object> profile(String ws, List<String> only, boolean registerJob) {
        String workspace = StrUtil.blankToDefault(ws, WS_DEFAULT).trim();
        List<String> targets = resolveTargets(workspace, only);
        int max = maxTables();
        if (targets.size() > max) {
            targets = new ArrayList<>(targets.subList(0, max));
        }
        String catalog = dsLauncher.sparkCatalog();
        int ok = 0;
        int partial = 0;
        int failed = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> metricLines = new ArrayList<>();
        long dayTs = GovLcStorageMetricsFormatter.dayCutEpochMs(Instant.now());
        int concurrency = profileConcurrency();
        if (concurrency <= 1 || targets.size() <= 1) {
            for (String fqn : targets) {
                ProfileOutcome outcome = profileOne(workspace, fqn, catalog, dayTs);
                rows.add(outcome.row());
                metricLines.addAll(outcome.lines());
            }
        } else {
            ExecutorService pool = Executors.newFixedThreadPool(concurrency);
            try {
                List<Future<ProfileOutcome>> futures = new ArrayList<>(targets.size());
                for (String fqn : targets) {
                    futures.add(pool.submit(() -> profileOne(workspace, fqn, catalog, dayTs)));
                }
                for (Future<ProfileOutcome> f : futures) {
                    try {
                        ProfileOutcome outcome = f.get(Math.max(timeoutMs() * 4L, 120_000L), TimeUnit.MILLISECONDS);
                        rows.add(outcome.row());
                        metricLines.addAll(outcome.lines());
                    } catch (Exception e) {
                        log.warn("storage profile concurrent wait failed: {}", e.getMessage());
                        Map<String, Object> err = new LinkedHashMap<>();
                        err.put("tableFqn", "?");
                        err.put("collectStatus", "failed");
                        err.put("error", StrUtil.maxLength(e.getMessage(), 200));
                        err.put("vmWritten", false);
                        rows.add(err);
                    }
                }
            } finally {
                pool.shutdownNow();
            }
        }
        for (Map<String, Object> one : rows) {
            String st = String.valueOf(one.get("collectStatus"));
            if ("ok".equals(st)) {
                ok++;
            } else if ("partial".equals(st)) {
                partial++;
            } else {
                failed++;
            }
        }

        Map<String, Object> daysToFull = daysToFullDeriver.deriveAndFormat(workspace, rows, dayTs);
        @SuppressWarnings("unchecked")
        List<String> ttfLines = (List<String>) daysToFull.getOrDefault("lines", List.of());
        if (ttfLines != null && !ttfLines.isEmpty()) {
            metricLines.addAll(ttfLines);
        }

        Map<String, Object> wsProj = projectWsStorageMetrics(workspace, dayTs);
        @SuppressWarnings("unchecked")
        List<String> wsLines = (List<String>) wsProj.getOrDefault("lines", List.of());
        if (wsLines != null && !wsLines.isEmpty()) {
            metricLines.addAll(wsLines);
        }

        Map<String, Object> vmResult = writeVm(metricLines);
        int vmSamples = metricLines.isEmpty() ? 0 : metricLines.size();
        Date adviceDt = java.sql.Date.valueOf(
                LocalDate.ofInstant(Instant.ofEpochMilli(dayTs), ZoneOffset.UTC));
        List<Map<String, Object>> adviceRows = adviceWriter.upsertFromProfile(workspace, adviceDt, rows);

        String runId = null;
        String workflow = null;
        Boolean degraded = null;
        if (registerJob) {
            String id = IdUtil.getSnowflakeNextIdStr();
            GovLcDsLauncher.LaunchResult launch = dsLauncher.launchProfileDaily(workspace, id, targets.size());
            GovLcRun run = new GovLcRun();
            run.setId(id);
            run.setRevision(1);
            run.setWs(workspace);
            run.setKind("profile");
            run.setDryRun(false);
            run.setOperator("job.lifecycle");
            run.setDsTaskId(launch.dsTaskId);
            run.setStartedAt(new Date());
            run.setFinishedAt(new Date());
            run.setStatus(failed == targets.size() && !targets.isEmpty() ? "failed" : "success");
            run.setDeleteFlag(NOT_DELETE);
            run.setCreateTime(new Date());
            run.setUpdateTime(new Date());
            Map<String, Object> metrics = launch.toMetricsJson();
            metrics.put("job", profileJobName(workspace));
            metrics.put("jobPrincipal", dsLauncher.jobPrincipal());
            metrics.put("writeback", "gov_lc_table_stat+vm");
            metrics.put("vm", Boolean.TRUE.equals(vmResult.get("ok")) && !Boolean.TRUE.equals(vmResult.get("skipped")));
            metrics.put("vmResult", vmResult);
            metrics.put("vmSampleLines", vmSamples);
            metrics.put("vmDayCutEpochMs", dayTs);
            metrics.put("daysToFull", daysToFull);
            metrics.put("wsStorageProjection", wsProj);
            metrics.put("adviceCount", adviceRows.size());
            metrics.put("ok", ok);
            metrics.put("partial", partial);
            metrics.put("failed", failed);
            metrics.put("profileConcurrency", concurrency);
            metrics.put("tables", rows);
            run.setMetricsJson(JSONUtil.toJsonStr(metrics));
            if ("failed".equals(run.getStatus())) {
                run.setErrorMsg("全部表画像失败");
            }
            runMapper.insert(run);
            runId = id;
            workflow = launch.workflowCode;
            degraded = launch.degraded;
            if (Boolean.TRUE.equals(vmResult.get("degraded"))) {
                degraded = true;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ws", workspace);
        out.put("job", profileJobName(workspace));
        out.put("jobPrincipal", dsLauncher.jobPrincipal());
        out.put("runId", runId);
        out.put("workflowCode", workflow);
        out.put("degraded", degraded);
        out.put("tableCount", targets.size());
        out.put("ok", ok);
        out.put("partial", partial);
        out.put("failed", failed);
        out.put("tables", rows);
        out.put("vm", vmResult);
        out.put("vmDayCutEpochMs", dayTs);
        out.put("daysToFull", daysToFull);
        out.put("wsStorageProjection", wsProj);
        out.put("adviceCount", adviceRows.size());
        out.put("advice", adviceRows);
        out.put("profileConcurrency", concurrency);
        out.put("source", "trino $files/$snapshots/$partitions → gov_lc_table_stat + lh_table_storage_* + lh_ws_storage_* + days_to_full");
        return out;
    }

    private Map<String, Object> writeVm(List<String> metricLines) {
        String body = GovLcStorageMetricsFormatter.joinBody(metricLines);
        Map<String, Object> result = victoriaMetricsClient.importPrometheus(body);
        result.put("sampleLines", metricLines.size());
        return result;
    }

    /**
     * 投影 {@code lh_ws_storage_{used,quota}_bytes}：用量来自本空间 {@code gov_lc_table_stat} 物理合计，
     * 配额来自 {@code gov_ws_quota}；日批可激活夜莺空间配额 80% 规则。
     */
    private Map<String, Object> projectWsStorageMetrics(String workspace, long dayTs) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> lines = new ArrayList<>();
        try {
            List<GovLcTableStat> stats = tableStatMapper.selectList(new QueryWrapper<GovLcTableStat>().lambda()
                    .eq(GovLcTableStat::getWs, workspace)
                    .eq(GovLcTableStat::getDeleteFlag, NOT_DELETE));
            long used = 0L;
            for (GovLcTableStat s : stats) {
                if (s == null) {
                    continue;
                }
                String st = StrUtil.blankToDefault(s.getCollectStatus(), "ok");
                if ("failed".equalsIgnoreCase(st)) {
                    continue;
                }
                used += s.getSizeBytes() == null ? 0L : Math.max(0L, s.getSizeBytes());
            }
            long quota = 0L;
            GovWsQuota q = govWsQuotaMapper.selectOne(new QueryWrapper<GovWsQuota>().lambda()
                    .eq(GovWsQuota::getWsCode, workspace)
                    .eq(GovWsQuota::getDeleteFlag, NOT_DELETE)
                    .last("LIMIT 1"));
            if (q != null && q.getStorageQuotaTb() != null) {
                quota = q.getStorageQuotaTb().multiply(BigDecimal.valueOf(TB)).longValue();
            }
            String owner = null;
            GovWs space = govWsMapper.selectOne(new QueryWrapper<GovWs>().lambda()
                    .eq(GovWs::getWsCode, workspace)
                    .eq(GovWs::getDeleteFlag, NOT_DELETE)
                    .last("LIMIT 1"));
            if (space != null) {
                owner = firstOwnerToken(space.getOwners());
            }
            lines.addAll(GovLcStorageMetricsFormatter.formatWsStorage(workspace, owner, used, quota, dayTs));
            out.put("ws", workspace);
            out.put("usedBytes", used);
            out.put("quotaBytes", quota);
            out.put("owner", GovLcStorageMetricsFormatter.blankOwner(owner));
            out.put("ok", true);
        } catch (Exception e) {
            log.warn("ws storage metrics projection soft-fail ws={}: {}", workspace, e.getMessage());
            out.put("ok", false);
            out.put("error", StrUtil.maxLength(e.getMessage(), 200));
        }
        out.put("lines", lines);
        return out;
    }

    private static String firstOwnerToken(String owners) {
        if (StrUtil.isBlank(owners)) {
            return null;
        }
        String raw = owners.trim();
        for (String sep : new String[]{",", ";", "|", "/", " "}) {
            if (raw.contains(sep)) {
                String[] parts = raw.split("[" + java.util.regex.Pattern.quote(sep) + "]+");
                for (String p : parts) {
                    if (StrUtil.isNotBlank(p)) {
                        return p.trim();
                    }
                }
            }
        }
        return raw;
    }

    private ProfileOutcome profileOne(String ws, String fqn, String defaultCatalog, long dayTs) {
        Map<String, Object> row = new LinkedHashMap<>();
        List<String> lines = new ArrayList<>();
        row.put("tableFqn", fqn);
        GovLcMetadataSql.TableRef ref;
        try {
            ref = GovLcMetadataSql.parse(fqn, defaultCatalog);
        } catch (IllegalArgumentException e) {
            mark(ws, fqn, null, "failed", e.getMessage(), null);
            row.put("collectStatus", "failed");
            row.put("error", e.getMessage());
            row.put("vmWritten", false);
            return new ProfileOutcome(row, lines);
        }
        try {
            Map<String, Object> files = query(GovLcMetadataSql.files(ref), ref);
            if (files == null) {
                mark(ws, fqn, ref, "failed", "Trino 不可达或 $files 无结果", null);
                row.put("collectStatus", "failed");
                row.put("error", "trino $files");
                row.put("vmWritten", false);
                return new ProfileOutcome(row, lines);
            }
            long fileCount = nvl(longVal(files, "file_count"));
            long active = nvl(longVal(files, "size_bytes"));
            long avg = nvl(longVal(files, "avg_file_bytes"));
            long small = nvl(longVal(files, "small_file_count"));

            String status = "ok";
            String error = null;
            Long physical = null;
            try {
                Map<String, Object> all = query(GovLcMetadataSql.allFiles(ref), ref);
                if (all == null) {
                    status = "partial";
                    error = "$all_files 不可用，可回收按 0 记，不把失败写进总量";
                } else {
                    physical = nvl(longVal(all, "size_bytes"));
                }
            } catch (Exception e) {
                status = "partial";
                error = "$all_files: " + StrUtil.maxLength(e.getMessage(), 200);
            }
            long reclaimable = 0;
            long total = active;
            if (physical != null) {
                total = Math.max(physical, active);
                reclaimable = Math.max(0, total - active);
            }

            Integer snapshots = null;
            try {
                Map<String, Object> snaps = query(GovLcMetadataSql.snapshots(ref), ref);
                if (snaps == null) {
                    status = "partial";
                    error = join(error, "$snapshots 不可用");
                } else {
                    snapshots = (int) Math.min(Integer.MAX_VALUE, nvl(longVal(snaps, "snapshot_count")));
                }
            } catch (Exception e) {
                status = "partial";
                error = join(error, "$snapshots: " + StrUtil.maxLength(e.getMessage(), 160));
            }

            Integer partitions = null;
            try {
                Map<String, Object> parts = query(GovLcMetadataSql.partitions(ref), ref);
                if (parts == null) {
                    status = "partial";
                    error = join(error, "$partitions 不可用");
                } else {
                    partitions = (int) Math.min(Integer.MAX_VALUE, nvl(longVal(parts, "partition_count")));
                }
            } catch (Exception e) {
                status = "partial";
                error = join(error, "$partitions: " + StrUtil.maxLength(e.getMessage(), 160));
            }

            Snapshot written = mark(ws, fqn, ref, status, error, new Numbers(
                    active, total, reclaimable, fileCount, avg, small, snapshots, partitions));
            String layer = written.layer;
            String owner = null;
            GovLcStorageAssetEnricher.AssetMeta meta = assetEnricher.resolve(fqn);
            if (meta != null) {
                if (StrUtil.isNotBlank(meta.owner())) {
                    owner = meta.owner();
                }
                if (StrUtil.isNotBlank(meta.layer())) {
                    layer = meta.layer();
                }
            }
            row.put("collectStatus", status);
            row.put("layer", layer);
            row.put("owner", GovLcStorageMetricsFormatter.blankOwner(owner));
            row.put("activeBytes", active);
            row.put("totalBytes", total);
            row.put("reclaimableBytes", reclaimable);
            row.put("fileCount", fileCount);
            row.put("avgFileBytes", avg);
            row.put("smallFileCount", small);
            row.put("snapshotCount", snapshots);
            row.put("partitionCount", partitions);
            row.put("growth7dPct", written.growth);
            if (error != null) {
                row.put("error", error);
            }
            // 失败隔离：failed 不写 VM；ok/partial 才入趋势点
            boolean writeVm = !"failed".equals(status);
            row.put("vmWritten", writeVm);
            if (writeVm) {
                double ratio = fileCount <= 0 ? 0.0 : (small * 1.0 / fileCount);
                lines.addAll(GovLcStorageMetricsFormatter.format(
                        new GovLcStorageMetricsFormatter.Sample(
                                fqn, ws, layer, owner, active, total, reclaimable,
                                fileCount, avg, ratio, snapshots, partitions),
                        dayTs));
            }
            return new ProfileOutcome(row, lines);
        } catch (Exception e) {
            log.warn("storage profile failed table={}: {}", fqn, e.getMessage());
            mark(ws, fqn, ref, "failed", StrUtil.maxLength(e.getMessage(), 400), null);
            row.put("collectStatus", "failed");
            row.put("error", e.getMessage());
            row.put("vmWritten", false);
            return new ProfileOutcome(row, lines);
        }
    }

    private Snapshot mark(String ws, String fqn, GovLcMetadataSql.TableRef ref, String collectStatus,
                          String error, Numbers numbers) {
        Date now = new Date();
        GovLcTableStat existing = tableStatMapper.selectOne(new QueryWrapper<GovLcTableStat>().lambda()
                .eq(GovLcTableStat::getWs, ws)
                .eq(GovLcTableStat::getTableFqn, fqn)
                .eq(GovLcTableStat::getDeleteFlag, NOT_DELETE)
                .last("LIMIT 1"));
        GovLcPolicy policy = policyMapper.selectOne(new QueryWrapper<GovLcPolicy>().lambda()
                .eq(GovLcPolicy::getWs, ws)
                .eq(GovLcPolicy::getTableFqn, fqn)
                .eq(GovLcPolicy::getDeleteFlag, NOT_DELETE)
                .last("LIMIT 1"));
        boolean insert = existing == null;
        long previousBytes = existing == null ? 0L : nvl(existing.getSizeBytes());
        Date previousAt = existing == null ? null : existing.getCollectedAt();
        BigDecimal previousGrowth = existing == null ? BigDecimal.ZERO : existing.getGrowth7dPct();
        GovLcTableStat stat = insert ? new GovLcTableStat() : existing;
        if (insert) {
            stat.setId(IdUtil.getSnowflakeNextIdStr());
            stat.setRevision(1);
            stat.setWs(ws);
            stat.setTableFqn(fqn);
            stat.setDeleteFlag(NOT_DELETE);
            stat.setCreateTime(now);
            stat.setSizeBytes(0L);
            stat.setFileCount(0L);
            stat.setAvgFileBytes(0L);
            stat.setGrowth7dPct(BigDecimal.ZERO);
        } else {
            stat.setRevision(nvlInt(stat.getRevision()) + 1);
        }
        stat.setCollectStatus(collectStatus);
        stat.setCollectError(error == null ? "" : StrUtil.maxLength(error, 500));
        stat.setCollectedAt(now);
        stat.setUpdateTime(now);
        String layer = existing == null ? null : existing.getLayer();
        if (policy != null) {
            if (StrUtil.isNotBlank(policy.getLayer())) {
                stat.setLayer(policy.getLayer());
                layer = policy.getLayer();
            }
            if (StrUtil.isBlank(stat.getPolicyLabel()) && policy.getKeepDays() != null) {
                stat.setPolicyLabel(policy.getKeepDays() + "天快照");
            }
        }
        BigDecimal growth = previousGrowth;
        if (numbers != null && !"failed".equals(collectStatus)) {
            BigDecimal nextGrowth = growthSince(previousBytes, previousAt, numbers.totalBytes, now);
            if (nextGrowth != null) {
                growth = nextGrowth;
            }
            stat.setGrowth7dPct(growth == null ? BigDecimal.ZERO : growth);
            stat.setActiveBytes(numbers.activeBytes);
            stat.setReclaimableBytes(numbers.reclaimableBytes);
            stat.setSizeBytes(numbers.totalBytes);
            stat.setFileCount(numbers.fileCount);
            stat.setAvgFileBytes(numbers.avgFileBytes);
            stat.setSmallFileCount(numbers.smallFileCount);
            BigDecimal ratio = numbers.fileCount <= 0
                    ? BigDecimal.ZERO
                    : BigDecimal.valueOf(numbers.smallFileCount * 1.0 / numbers.fileCount)
                    .setScale(4, RoundingMode.HALF_UP);
            stat.setSmallFileRatio(ratio);
            stat.setSnapshotCount(numbers.snapshotCount);
            boolean warn = ratio.doubleValue() > 0.30
                    || (numbers.avgFileBytes > 0 && numbers.avgFileBytes < GovLcMetadataSql.SMALL_FILE_BYTES);
            stat.setStatus(warn ? "warn" : "ok");
            stat.setRemark("job.storage.profile_daily");
        }
        if (insert) {
            tableStatMapper.insert(stat);
        } else {
            tableStatMapper.updateById(stat);
        }
        return new Snapshot(growth, layer);
    }

    private BigDecimal growthSince(long previous, Date previousAt, long current, Date now) {
        if (previous <= 0 || previousAt == null) {
            return BigDecimal.ZERO;
        }
        long ageMs = now.getTime() - previousAt.getTime();
        if (ageMs < 20L * 3600_000L) {
            return null;
        }
        double days = ageMs / 86_400_000.0;
        double pct = (current - previous) * 100.0 / previous;
        if (days >= 0.5 && days < 30) {
            pct = pct * (7.0 / days);
        }
        return BigDecimal.valueOf(pct).setScale(2, RoundingMode.HALF_UP);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> query(String sql, GovLcMetadataSql.TableRef ref) {
        TrinoClient.ExecuteOptions opts = TrinoClient.ExecuteOptions.job(5);
        opts.catalog = ref.catalog();
        opts.schema = ref.schema();
        opts.timeoutMs = timeoutMs();
        opts.source = "job.lifecycle";
        opts.clientTags = "job.lifecycle,storage-profile";
        Map<String, Object> exec = trinoClient.execute(sql, opts);
        if (Boolean.TRUE.equals(exec.get("degraded"))) {
            throw new IllegalStateException(String.valueOf(exec.get("message")));
        }
        Object rows = exec.get("rows");
        if (!(rows instanceof List<?> list) || list.isEmpty() || !(list.get(0) instanceof Map<?, ?> map)) {
            return null;
        }
        return (Map<String, Object>) map;
    }

    /**
     * 表枚举顺序：显式 only → Trino {@code system.iceberg_tables} → Grav Catalog → policy ∪ table_stat。
     * 不支持 iceberg_tables 时静默回退（见 ops/storage-collect/DEPLOY.md §7）。
     */
    private List<String> resolveTargets(String ws, List<String> only) {
        if (only != null && !only.isEmpty()) {
            Set<String> set = new LinkedHashSet<>();
            for (String f : only) {
                if (StrUtil.isNotBlank(f)) {
                    set.add(f.trim());
                }
            }
            return new ArrayList<>(set);
        }
        Set<String> set = new LinkedHashSet<>();
        int fromIceberg = addFromSystemIcebergTables(set);
        int fromGrav = 0;
        if (fromIceberg == 0) {
            fromGrav = addFromGravitino(set);
        }
        List<GovLcPolicy> policies = policyMapper.selectList(new QueryWrapper<GovLcPolicy>().lambda()
                .eq(GovLcPolicy::getWs, ws)
                .eq(GovLcPolicy::getDeleteFlag, NOT_DELETE)
                .eq(GovLcPolicy::getStatus, "active")
                .orderByAsc(GovLcPolicy::getTableFqn));
        for (GovLcPolicy p : policies) {
            if (StrUtil.isNotBlank(p.getTableFqn())) {
                set.add(p.getTableFqn().trim());
            }
        }
        List<GovLcTableStat> stats = tableStatMapper.selectList(new QueryWrapper<GovLcTableStat>().lambda()
                .eq(GovLcTableStat::getWs, ws)
                .eq(GovLcTableStat::getDeleteFlag, NOT_DELETE));
        for (GovLcTableStat s : stats) {
            if (StrUtil.isNotBlank(s.getTableFqn())) {
                set.add(s.getTableFqn().trim());
            }
        }
        log.info("storage profile targets ws={} iceberg={} gravitino={} total={}",
                ws, fromIceberg, fromGrav, set.size());
        return new ArrayList<>(set);
    }

    /** @return 新增表数；0=不可用或空 */
    private int addFromSystemIcebergTables(Set<String> set) {
        String catalog = dsLauncher.sparkCatalog();
        String sql = "SELECT table_schema, table_name FROM system.iceberg_tables";
        try {
            TrinoClient.ExecuteOptions opts = TrinoClient.ExecuteOptions.job(5);
            opts.catalog = catalog;
            opts.timeoutMs = Math.min(30_000, timeoutMs());
            opts.source = "job.lifecycle";
            opts.clientTags = "job.lifecycle,storage-profile,enum";
            Map<String, Object> exec = trinoClient.execute(sql, opts);
            if (Boolean.TRUE.equals(exec.get("degraded"))) {
                log.info("system.iceberg_tables unavailable: {}", exec.get("message"));
                return 0;
            }
            Object rows = exec.get("rows");
            if (!(rows instanceof List<?> list) || list.isEmpty()) {
                return 0;
            }
            int before = set.size();
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                String schema = strCell(m, "table_schema", "TABLE_SCHEMA");
                String name = strCell(m, "table_name", "TABLE_NAME");
                if (StrUtil.isBlank(name)) {
                    continue;
                }
                if (StrUtil.isNotBlank(schema)) {
                    set.add(schema.trim() + "." + name.trim());
                } else {
                    set.add(name.trim());
                }
            }
            return set.size() - before;
        } catch (Exception e) {
            log.info("system.iceberg_tables enum skipped: {}", e.getMessage());
            return 0;
        }
    }

    private int addFromGravitino(Set<String> set) {
        try {
            LhProperties.Gravitino g = lhProperties.getGravitino();
            if (g == null || StrUtil.isBlank(g.getMetalake()) || StrUtil.isBlank(g.getCatalog())) {
                return 0;
            }
            String metalake = g.getMetalake();
            String catalog = g.getCatalog();
            List<String> schemas = gravitinoClient.listSchemas(metalake, catalog);
            if (schemas == null || schemas.isEmpty()) {
                return 0;
            }
            int before = set.size();
            int budget = maxTables();
            for (String schema : schemas) {
                if (set.size() - before >= budget) {
                    break;
                }
                if (StrUtil.isBlank(schema) || schema.startsWith("information_")) {
                    continue;
                }
                try {
                    List<String> tables = gravitinoClient.listTables(metalake, catalog, schema);
                    if (tables == null) {
                        continue;
                    }
                    for (String t : tables) {
                        if (StrUtil.isNotBlank(t)) {
                            set.add(schema.trim() + "." + t.trim());
                        }
                        if (set.size() - before >= budget) {
                            break;
                        }
                    }
                } catch (Exception e) {
                    log.debug("gravitino listTables {}.{}: {}", catalog, schema, e.getMessage());
                }
            }
            return set.size() - before;
        } catch (Exception e) {
            log.info("gravitino catalog enum skipped: {}", e.getMessage());
            return 0;
        }
    }

    private static String strCell(Map<?, ?> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v == null) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (e.getKey() != null && k.equalsIgnoreCase(String.valueOf(e.getKey()))) {
                        v = e.getValue();
                        break;
                    }
                }
            }
            if (v != null && StrUtil.isNotBlank(String.valueOf(v))) {
                return String.valueOf(v).trim();
            }
        }
        return null;
    }

    private String profileJobName(String ws) {
        String base = lhProperties.getLifecycle() != null
                ? lhProperties.getLifecycle().getProfileWorkflowName() : null;
        base = StrUtil.blankToDefault(base, "job.storage.profile_daily");
        if (WS_DEFAULT.equals(ws)) {
            return base;
        }
        return base + "_" + GovLcProcedureSql.safeIdent(ws);
    }

    private int maxTables() {
        if (lhProperties.getLifecycle() == null) {
            return 200;
        }
        return Math.max(1, lhProperties.getLifecycle().getProfileMaxTables());
    }

    private int profileConcurrency() {
        if (lhProperties.getLifecycle() == null) {
            return 4;
        }
        int n = lhProperties.getLifecycle().getProfileConcurrency();
        return Math.min(8, Math.max(1, n <= 0 ? 4 : n));
    }

    private int timeoutMs() {
        if (lhProperties.getLifecycle() == null) {
            return 60_000;
        }
        return Math.max(5_000, lhProperties.getLifecycle().getProfileTableTimeoutMs());
    }

    private static String join(String a, String b) {
        if (StrUtil.isBlank(a)) {
            return b;
        }
        return a + "; " + b;
    }

    private static long nvl(Long v) {
        return v == null ? 0L : v;
    }

    private static int nvlInt(Integer v) {
        return v == null ? 1 : v;
    }

    private static Long longVal(Map<String, Object> row, String key) {
        if (row == null) {
            return null;
        }
        Object v = row.get(key);
        if (v == null) {
            for (Map.Entry<String, Object> e : row.entrySet()) {
                if (key.equalsIgnoreCase(e.getKey())) {
                    v = e.getValue();
                    break;
                }
            }
        }
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return new BigDecimal(String.valueOf(v).trim()).longValue();
        } catch (Exception e) {
            return null;
        }
    }

    private record Numbers(long activeBytes, long totalBytes, long reclaimableBytes, long fileCount,
                           long avgFileBytes, long smallFileCount, Integer snapshotCount, Integer partitionCount) {
    }

    private record Snapshot(BigDecimal growth, String layer) {
    }

    private record ProfileOutcome(Map<String, Object> row, List<String> lines) {
    }
}
