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
import vip.xiaonuo.lh.core.engine.TrinoClient;
import vip.xiaonuo.lh.core.engine.VictoriaMetricsClient;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcPolicy;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcRun;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcTableStat;
import vip.xiaonuo.lh.modular.lifecycle.mapper.GovLcPolicyMapper;
import vip.xiaonuo.lh.modular.lifecycle.mapper.GovLcRunMapper;
import vip.xiaonuo.lh.modular.lifecycle.mapper.GovLcTableStatMapper;

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
        for (String fqn : targets) {
            Map<String, Object> one = profileOne(workspace, fqn, catalog, dayTs, metricLines);
            rows.add(one);
            String st = String.valueOf(one.get("collectStatus"));
            if ("ok".equals(st)) {
                ok++;
            } else if ("partial".equals(st)) {
                partial++;
            } else {
                failed++;
            }
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
            metrics.put("adviceCount", adviceRows.size());
            metrics.put("ok", ok);
            metrics.put("partial", partial);
            metrics.put("failed", failed);
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
        out.put("adviceCount", adviceRows.size());
        out.put("advice", adviceRows);
        out.put("source", "trino $files/$snapshots/$partitions → gov_lc_table_stat + lh_table_storage_*");
        return out;
    }

    private Map<String, Object> writeVm(List<String> metricLines) {
        String body = GovLcStorageMetricsFormatter.joinBody(metricLines);
        Map<String, Object> result = victoriaMetricsClient.importPrometheus(body);
        result.put("sampleLines", metricLines.size());
        return result;
    }

    private Map<String, Object> profileOne(String ws, String fqn, String defaultCatalog,
                                           long dayTs, List<String> metricLines) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tableFqn", fqn);
        GovLcMetadataSql.TableRef ref;
        try {
            ref = GovLcMetadataSql.parse(fqn, defaultCatalog);
        } catch (IllegalArgumentException e) {
            mark(ws, fqn, null, "failed", e.getMessage(), null);
            row.put("collectStatus", "failed");
            row.put("error", e.getMessage());
            row.put("vmWritten", false);
            return row;
        }
        try {
            Map<String, Object> files = query(GovLcMetadataSql.files(ref), ref);
            if (files == null) {
                mark(ws, fqn, ref, "failed", "Trino 不可达或 $files 无结果", null);
                row.put("collectStatus", "failed");
                row.put("error", "trino $files");
                row.put("vmWritten", false);
                return row;
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
            row.put("collectStatus", status);
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
                metricLines.addAll(GovLcStorageMetricsFormatter.format(
                        new GovLcStorageMetricsFormatter.Sample(
                                fqn, ws, written.layer, active, total, reclaimable,
                                fileCount, avg, ratio, snapshots, partitions),
                        dayTs));
            }
            return row;
        } catch (Exception e) {
            log.warn("storage profile failed table={}: {}", fqn, e.getMessage());
            mark(ws, fqn, ref, "failed", StrUtil.maxLength(e.getMessage(), 400), null);
            row.put("collectStatus", "failed");
            row.put("error", e.getMessage());
            row.put("vmWritten", false);
            return row;
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
        return new ArrayList<>(set);
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
}
