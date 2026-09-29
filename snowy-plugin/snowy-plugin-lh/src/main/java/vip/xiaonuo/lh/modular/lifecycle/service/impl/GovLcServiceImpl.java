package vip.xiaonuo.lh.modular.lifecycle.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.xiaonuo.auth.core.pojo.SaBaseLoginUser;
import vip.xiaonuo.auth.core.util.StpLoginUserUtil;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.common.page.CommonPageRequest;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcJobStep;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcOrphanScan;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcPolicy;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcRun;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcTableStat;
import vip.xiaonuo.lh.modular.lifecycle.mapper.GovLcJobStepMapper;
import vip.xiaonuo.lh.modular.lifecycle.mapper.GovLcOrphanScanMapper;
import vip.xiaonuo.lh.modular.lifecycle.mapper.GovLcPolicyMapper;
import vip.xiaonuo.lh.modular.lifecycle.mapper.GovLcRunMapper;
import vip.xiaonuo.lh.modular.lifecycle.mapper.GovLcTableStatMapper;
import vip.xiaonuo.lh.modular.lifecycle.param.GovLcOrphanScanParam;
import vip.xiaonuo.lh.modular.lifecycle.param.GovLcPolicyUpsertParam;
import vip.xiaonuo.lh.modular.lifecycle.param.GovLcRunNowParam;
import vip.xiaonuo.lh.modular.lifecycle.param.GovLcRunPageParam;
import vip.xiaonuo.lh.modular.lifecycle.param.GovLcTableActionParam;
import vip.xiaonuo.lh.modular.lifecycle.result.GovLcPolicyVo;
import vip.xiaonuo.lh.modular.lifecycle.result.GovLcRunVo;
import vip.xiaonuo.lh.modular.lifecycle.service.GovLcService;
import vip.xiaonuo.lh.modular.lifecycle.service.GovLcStorageService;
import vip.xiaonuo.lh.modular.compliance.entity.GovDelRequest;
import vip.xiaonuo.lh.modular.compliance.mapper.GovDelRequestMapper;
import vip.xiaonuo.lh.modular.lifecycle.support.GovLcDsLauncher;
import vip.xiaonuo.lh.modular.lifecycle.support.GovLcRunEffectWriter;
import vip.xiaonuo.lh.modular.lifecycle.support.GovLcStorageAdviceWriter;
import vip.xiaonuo.lh.modular.lifecycle.support.GovLcStorageChangePointWriter;
import vip.xiaonuo.lh.modular.export.service.ExportBoardService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 生命周期：策略 SoT + DS Iceberg Procedure 真接线。
 */
@Service
public class GovLcServiceImpl implements GovLcService {

    private static final String WS_DEFAULT = "default";
    private static final String NOT_DELETE = "NOT_DELETE";

    private static final List<String[]> DAILY_STEPS = List.of(
            new String[]{"expire_snapshots", "快照过期", "ods/dwd 按保留策略逻辑删除"},
            new String[]{"rewrite_data_files", "小文件合并（目标 256MB）", "L1/L2 表 compaction"},
            new String[]{"remove_orphan_files", "孤儿文件清理", "日作业 dry-run；物理删须安全窗"},
            new String[]{"expire_partitions", "分区过期", "ODS 归档候选标记"}
    );

    @Resource
    private GovLcPolicyMapper policyMapper;
    @Resource
    private GovLcRunMapper runMapper;
    @Resource
    private GovLcOrphanScanMapper orphanScanMapper;
    @Resource
    private GovLcTableStatMapper tableStatMapper;
    @Resource
    private GovLcJobStepMapper jobStepMapper;
    @Resource
    private GovLcDsLauncher dsLauncher;
    @Resource
    private GovLcStorageService govLcStorageService;
    @Resource
    private GovLcStorageAdviceWriter adviceWriter;
    @Resource
    private GovLcStorageChangePointWriter changePointWriter;
    @Resource
    private GovLcRunEffectWriter effectWriter;
    @Resource
    private GovDelRequestMapper govDelRequestMapper;
    @Resource
    private ExportBoardService exportBoardService;

    @Override
    public Map<String, Object> overview(String ws) {
        String workspace = listWs(ws);
        // 总存储 = 物理口径，与 /lh/lifecycle/storage/summary 同源（三口径）；无画像时为 0
        Map<String, Object> summary = govLcStorageService.summary(workspace, "30d");
        long physicalBytes = toLong(summary.get("physicalBytes"));
        long activeBytes = toLong(summary.get("activeBytes"));
        long reclaimableBytes = toLong(summary.get("reclaimableBytes"));

        List<GovLcTableStat> stats = listStats(workspace);
        long warnTables = stats.stream()
                .filter(s -> "warn".equalsIgnoreCase(StrUtil.blankToDefault(s.getStatus(), "ok"))
                        || "anomaly".equalsIgnoreCase(StrUtil.blankToDefault(s.getStatus(), "")))
                .count();

        Date monthStart = monthStart();
        List<GovLcRun> monthRuns = runMapper.selectList(new QueryWrapper<GovLcRun>().lambda()
                .eq(StrUtil.isNotBlank(workspace), GovLcRun::getWs, workspace)
                .eq(GovLcRun::getDeleteFlag, NOT_DELETE)
                .ge(GovLcRun::getCreateTime, monthStart)
                .eq(GovLcRun::getStatus, "success"));
        long compactCount = monthRuns.stream().filter(r -> "compact".equals(r.getKind())).count();
        long cleanedBytes = sumCleanedBytes(monthRuns);
        long cleanedGb = cleanedBytes > 0
                ? Math.round(cleanedBytes / (1024.0 * 1024 * 1024))
                : 0L;

        List<Map<String, Object>> archiveList = archiveCandidates(workspace);
        long archivePartitions = archiveList.stream()
                .mapToLong(r -> toLong(r.get("candidatePartitions")))
                .sum();
        // 尚无 $partitions 真值时 KPI 用「有过期策略的表数」作候选表水位，不为假分区数
        long archiveKpi = archivePartitions > 0 ? archivePartitions : archiveList.size();

        long compliancePending = countCompliancePending(workspace);
        Map<String, Object> hotWarmCold = tierBytesFromBuckets(workspace);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ws", workspace);
        out.put("totalStorageBytes", physicalBytes);
        out.put("totalStorageTb", bytesToTb(physicalBytes));
        out.put("storageCaliber", "physical");
        out.put("activeBytes", activeBytes);
        out.put("reclaimableBytes", reclaimableBytes);
        out.put("reclaimablePct", summary.get("reclaimablePct"));
        out.put("storageRange", summary.get("range"));
        out.put("monthCleanedGb", cleanedGb);
        out.put("monthCleanedBytes", cleanedBytes);
        out.put("compactSuccessCount", compactCount);
        out.put("archiveCandidatePartitions", archiveKpi);
        out.put("archiveCandidateTables", archiveList.size());
        out.put("archiveUnit", archivePartitions > 0 ? "partitions" : "tables");
        out.put("compliancePending", compliancePending);
        out.put("warnTableCount", warnTables);
        out.put("hotWarmCold", hotWarmCold);
        out.put("source", summary.get("source"));
        out.put("caliberNote", "总存储=物理口径；与 storage/summary 同源；无水位时 KPI 为 0/空态");
        // 双轴回收叙事：湖内分区归档 ≠ 出湖授权到期停作业（不双写）
        out.put("reclaimAxes", buildReclaimAxes(workspace, archiveList.size(),
                archivePartitions > 0 ? archivePartitions : archiveList.size()));
        return out;
    }

    /**
     * 文案+深链对齐：湖内分区过期/冷桶归档 vs 出湖 ticket 到期回收（停 DAG + 通知删副本）。
     */
    private Map<String, Object> buildReclaimAxes(String workspace, long archiveTables, long archiveMetric) {
        Map<String, Object> axes = new LinkedHashMap<>();
        Map<String, Object> lake = new LinkedHashMap<>();
        lake.put("kind", "lake_partition_archive");
        lake.put("label", "湖内分区归档候选");
        lake.put("tables", archiveTables);
        lake.put("metric", archiveMetric);
        lake.put("unit", archiveMetric > archiveTables ? "partitions" : "tables");
        lake.put("note", "Iceberg 分区过期 → 冷桶迁移模板；SoT 在本页 archive-candidates");
        lake.put("deepLink", "/lifecycle?focus=archive");
        axes.put("lakePartitionArchive", lake);

        Map<String, Object> exp = new LinkedHashMap<>();
        exp.put("kind", "export_expire_reclaim");
        exp.put("label", "出湖授权到期回收");
        exp.put("note", "停 ETL sink 作业 + 通知下游删副本；SoT 在 /export（不写 gov_lc_*）");
        exp.put("deepLink", "/export?focus=expire");
        int expiring = 0;
        try {
            Map<String, Object> sum = exportBoardService.summary(workspace);
            if (sum != null && sum.get("expiringSoon") instanceof Number n) {
                expiring = n.intValue();
            }
        } catch (Exception ignored) {
            /* soft：出湖模块不可用时仍返回深链文案 */
        }
        exp.put("expiringSoon", expiring);
        axes.put("exportExpireReclaim", exp);
        return axes;
    }

    @Override
    public List<Map<String, Object>> archiveCandidates(String ws) {
        String workspace = listWs(ws);
        List<GovLcPolicy> policies = policyMapper.selectList(new QueryWrapper<GovLcPolicy>().lambda()
                .eq(StrUtil.isNotBlank(workspace), GovLcPolicy::getWs, workspace)
                .eq(GovLcPolicy::getDeleteFlag, NOT_DELETE)
                .eq(GovLcPolicy::getStatus, "active")
                .isNotNull(GovLcPolicy::getPartitionExpireDays)
                .gt(GovLcPolicy::getPartitionExpireDays, 0)
                .orderByAsc(GovLcPolicy::getTableFqn));
        List<Map<String, Object>> out = new ArrayList<>();
        for (GovLcPolicy p : policies) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ws", p.getWs());
            m.put("tableFqn", p.getTableFqn());
            m.put("layer", p.getLayer());
            m.put("partitionExpireDays", p.getPartitionExpireDays());
            GovLcTableStat st = tableStatMapper.selectOne(new QueryWrapper<GovLcTableStat>().lambda()
                    .eq(GovLcTableStat::getWs, p.getWs())
                    .eq(GovLcTableStat::getTableFqn, p.getTableFqn())
                    .eq(GovLcTableStat::getDeleteFlag, NOT_DELETE)
                    .last("LIMIT 1"));
            boolean profiled = st != null && ("ok".equalsIgnoreCase(StrUtil.blankToDefault(st.getCollectStatus(), ""))
                    || "partial".equalsIgnoreCase(StrUtil.blankToDefault(st.getCollectStatus(), "")));
            // 分区数真值走 VM lh_table_storage_partitions / tables/detail；$partitions 未回写本表时为 0（空态合法）
            int parts = 0;
            m.put("partitionCount", parts);
            m.put("candidatePartitions", parts);
            m.put("coldBucketPrefix", "s3a://archive/iceberg");
            m.put("status", profiled ? "profiled" : "policy_only");
            m.put("hasExpirePolicy", true);
            m.put("kind", "lake_partition_archive");
            m.put("semantics", "lake_partition_expire");
            m.put("notExportReclaim", true);
            m.put("hint", "湖内分区过期/冷桶归档；出湖授权到期请走 /export?focus=expire（不双写）");
            m.put("deepLink", Map.of(
                    "lifecycle", "/lifecycle?table=" + p.getTableFqn() + "&action=archive&focus=archive",
                    "catalog", "/catalog?q=" + p.getTableFqn(),
                    "exportExpire", "/export?focus=expire"
            ));
            out.add(m);
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> compliancePreview(String ws, Integer limit) {
        String workspace = listWs(ws);
        int lim = limit == null || limit <= 0 ? 10 : Math.min(limit, 50);
        Set<String> open = Set.of(
                "assessing", "pending_approval", "scheduled", "executing", "verifying",
                "partial_failed", "on_hold", "restricted", "archived");
        List<GovDelRequest> rows = govDelRequestMapper.selectList(new QueryWrapper<GovDelRequest>().lambda()
                .eq(StrUtil.isNotBlank(workspace), GovDelRequest::getWs, workspace)
                .eq(GovDelRequest::getDeleteFlag, NOT_DELETE)
                .in(GovDelRequest::getStatus, open)
                .orderByDesc(GovDelRequest::getCreateTime)
                .last("LIMIT " + lim));
        List<Map<String, Object>> out = new ArrayList<>();
        for (GovDelRequest r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getReqNo());
            m.put("reqId", r.getId());
            m.put("reqNo", r.getReqNo());
            m.put("subject", StrUtil.blankToDefault(r.getSubjectMasked(), "—"));
            m.put("type", r.getReqType());
            m.put("impact", StrUtil.blankToDefault(r.getScopeLabel(), "—"));
            m.put("approval", statusApprovalLabel(r.getStatus()));
            m.put("approvalPending", "pending_approval".equals(r.getStatus()) || "assessing".equals(r.getStatus()));
            m.put("status", statusZh(r.getStatus()));
            m.put("statusRaw", r.getStatus());
            m.put("statusCls", statusCls(r.getStatus()));
            m.put("deepLink", "/compliance?reqNo=" + StrUtil.blankToDefault(r.getReqNo(), r.getId()));
            out.add(m);
        }
        return out;
    }

    @Override
    public Map<String, Object> latestJobs(String ws) {
        String workspace = listWs(ws);
        GovLcRun latestDaily = runMapper.selectOne(new QueryWrapper<GovLcRun>().lambda()
                .eq(StrUtil.isNotBlank(workspace), GovLcRun::getWs, workspace)
                .eq(GovLcRun::getKind, "daily")
                .eq(GovLcRun::getDeleteFlag, NOT_DELETE)
                .orderByDesc(GovLcRun::getCreateTime)
                .last("LIMIT 1"));
        String batchId = latestDaily != null ? latestDaily.getBatchId() : null;
        List<GovLcJobStep> steps = List.of();
        if (StrUtil.isNotBlank(batchId)) {
            steps = jobStepMapper.selectList(new QueryWrapper<GovLcJobStep>().lambda()
                    .eq(GovLcJobStep::getBatchId, batchId)
                    .eq(GovLcJobStep::getDeleteFlag, NOT_DELETE)
                    .orderByAsc(GovLcJobStep::getStepNo));
        }
        List<Map<String, Object>> stepVos = steps.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("step", s.getStepNo());
            m.put("name", s.getStepName());
            m.put("desc", s.getStepDesc());
            m.put("detail", s.getDetail());
            m.put("durationSec", s.getDurationSec());
            m.put("status", s.getStatus());
            return m;
        }).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ws", workspace);
        out.put("batchId", batchId);
        out.put("runId", latestDaily != null ? latestDaily.getId() : null);
        out.put("status", latestDaily != null ? latestDaily.getStatus() : "unknown");
        out.put("dsTaskId", latestDaily != null ? latestDaily.getDsTaskId() : null);
        out.put("steps", stepVos);
        return out;
    }

    @Override
    public List<Map<String, Object>> topStorage(String ws, Integer limit) {
        int lim = limit == null || limit <= 0 ? 20 : Math.min(limit, 100);
        Map<String, Object> page = govLcStorageService.tables(ws, "7d", null, "all", "totalBytes", "desc", 1, lim);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) page.getOrDefault("list", List.of());
        return list;
    }

    @Override
    public Map<String, Object> stats(String ws, String tableFqn) {
        if (StrUtil.isBlank(tableFqn)) {
            throw new CommonException("table 不能为空");
        }
        String workspace = listWs(ws);
        String fqn = tableFqn.trim();
        GovLcTableStat stat = tableStatMapper.selectOne(new QueryWrapper<GovLcTableStat>().lambda()
                .eq(StrUtil.isNotBlank(workspace), GovLcTableStat::getWs, workspace)
                .eq(GovLcTableStat::getTableFqn, fqn)
                .eq(GovLcTableStat::getDeleteFlag, NOT_DELETE)
                .last("LIMIT 1"));
        GovLcPolicy policy = findPolicy(workspace, fqn);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ws", workspace);
        out.put("tableFqn", fqn);
        if (stat != null) {
            out.putAll(statToMap(stat));
        } else {
            out.put("sizeBytes", 0);
            out.put("fileCount", 0);
            out.put("avgFileBytes", 0);
            out.put("growth7dPct", 0);
            out.put("status", "unknown");
        }
        boolean overThreshold = nvl(stat != null ? stat.getFileCount() : null) > 50
                || (stat != null && nvl(stat.getAvgFileBytes()) > 0 && nvl(stat.getAvgFileBytes()) < 32L * 1024 * 1024);
        out.put("compactThresholdHit", overThreshold);
        out.put("thresholds", Map.of(
                "maxFilesPerPartition", 50,
                "minAvgFileMb", 32
        ));
        if (policy != null) {
            out.put("policy", toPolicyVo(policy));
            out.put("orphanOlderDays", policy.getOrphanOlderDays());
            out.put("orphanSafetyHours", policy.getOrphanSafetyHours());
        } else {
            out.put("policy", null);
            out.put("orphanOlderDays", 7);
            out.put("orphanSafetyHours", 72);
        }
        return out;
    }

    @Override
    public List<GovLcPolicyVo> listPolicies(String ws) {
        String workspace = listWs(ws);
        return policyMapper.selectList(new QueryWrapper<GovLcPolicy>().lambda()
                        .eq(StrUtil.isNotBlank(workspace), GovLcPolicy::getWs, workspace)
                        .eq(GovLcPolicy::getDeleteFlag, NOT_DELETE)
                        .orderByAsc(GovLcPolicy::getTableFqn))
                .stream()
                .map(this::toPolicyVo)
                .toList();
    }

    @Override
    public GovLcPolicyVo getPolicy(String ws, String tableFqn) {
        if (StrUtil.isBlank(tableFqn)) {
            throw new CommonException("table 不能为空");
        }
        GovLcPolicy policy = findPolicy(listWs(ws), tableFqn.trim());
        if (policy == null) {
            throw new CommonException("策略不存在：" + tableFqn);
        }
        return toPolicyVo(policy);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovLcPolicyVo upsertPolicy(GovLcPolicyUpsertParam param) {
        String workspace = wsOrDefault(param.getWs());
        String fqn = param.getTableFqn().trim();
        GovLcPolicy existing = findPolicy(workspace, fqn);
        Date now = new Date();
        boolean created = existing == null;
        if (existing == null) {
            GovLcPolicy p = new GovLcPolicy();
            p.setId(IdUtil.getSnowflakeNextIdStr());
            p.setRevision(1);
            p.setStatus(StrUtil.blankToDefault(param.getStatus(), "active"));
            p.setWs(workspace);
            p.setTableFqn(fqn);
            applyPolicyFields(p, param);
            p.setDeleteFlag(NOT_DELETE);
            p.setCreateTime(now);
            p.setUpdateTime(now);
            policyMapper.insert(p);
            changePointWriter.writeTablePoint(
                    workspace, fqn, "policy_upsert",
                    created ? "新建生命周期策略" : "更新生命周期策略",
                    p.getId());
            return toPolicyVo(p);
        }
        existing.setRevision(nvlInt(existing.getRevision()) + 1);
        if (StrUtil.isNotBlank(param.getStatus())) {
            existing.setStatus(param.getStatus().trim().toLowerCase(Locale.ROOT));
        }
        applyPolicyFields(existing, param);
        existing.setUpdateTime(now);
        policyMapper.updateById(existing);
        changePointWriter.writeTablePoint(
                workspace, fqn, "policy_upsert",
                "更新生命周期策略 rev=" + existing.getRevision(),
                existing.getId());
        return toPolicyVo(existing);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovLcRunVo compact(GovLcTableActionParam param) {
        return enqueueTableAction(param, "compact");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovLcRunVo expire(GovLcTableActionParam param) {
        return enqueueTableAction(param, "expire");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovLcRunVo complianceDeleteIceberg(GovLcTableActionParam param) {
        if (param == null || StrUtil.isBlank(param.getTableFqn())) {
            throw new CommonException("tableFqn 不能为空");
        }
        if (StrUtil.isBlank(param.getReqNo())) {
            throw new CommonException("合规硬删 DAG 必须携带 reqNo");
        }
        if (StrUtil.isBlank(param.getIdColumn()) || StrUtil.isBlank(param.getSubjectIdHash())) {
            throw new CommonException("合规硬删 DAG 需要 idColumn 与 subjectIdHash");
        }
        // 强制定向过期参数；assert 校验工单状态
        param.setRetainLast(1);
        assertComplianceOverride(param, "compliance_delete");

        String workspace = wsOrDefault(param.getWs());
        String fqn = param.getTableFqn().trim();
        GovLcPolicy policy = findOrDefaultPolicy(workspace, fqn);
        String operator = currentOperator();
        Date now = new Date();
        String runId = IdUtil.getSnowflakeNextIdStr();

        GovLcDsLauncher.LaunchResult launch = dsLauncher.launchComplianceDelete(
                fqn,
                param.getIdColumn().trim(),
                param.getSubjectIdHash().trim(),
                param.getReqNo().trim(),
                policy,
                runId);

        GovLcRun run = new GovLcRun();
        run.setId(runId);
        run.setRevision(1);
        run.setWs(workspace);
        run.setKind("compliance_delete");
        run.setTableFqn(fqn);
        run.setDsTaskId(launch.dsTaskId);
        run.setDryRun(false);
        run.setOperator(operator);
        run.setRemark(StrUtil.blankToDefault(param.getRemark(),
                "job.compliance.delete.iceberg " + param.getReqNo().trim()));
        run.setReqNo(param.getReqNo().trim());
        run.setRetainLast(1);
        run.setStartedAt(now);
        run.setMetricsJson(JSONUtil.toJsonStr(launch.toMetricsJson()));
        run.setDeleteFlag(NOT_DELETE);
        run.setCreateTime(now);
        run.setUpdateTime(now);
        if (StrUtil.isNotBlank(launch.processInstanceId)) {
            run.setStatus("running");
        } else if (launch.degraded) {
            run.setStatus("failed");
            run.setErrorMsg(launch.message);
            run.setFinishedAt(now);
        } else {
            run.setStatus("queued");
        }
        runMapper.insert(run);
        return toRunVo(run);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> orphanScan(GovLcOrphanScanParam param) {
        boolean dryRun = param.getDryRun() == null || Boolean.TRUE.equals(param.getDryRun());
        if (!dryRun) {
            throw new CommonException("P0 仅支持 orphan dry-run；物理删走日作业安全窗后的专项任务");
        }
        String workspace = wsOrDefault(param.getWs());
        String operator = currentOperator();
        Date now = new Date();
        String runId = IdUtil.getSnowflakeNextIdStr();

        // 若指定表则走 Iceberg remove_orphan_files dry_run；否则对策略表逐表提交（取首表）或登记扫描投影
        List<GovLcPolicy> policies = policyMapper.selectList(new QueryWrapper<GovLcPolicy>().lambda()
                .eq(StrUtil.isNotBlank(workspace), GovLcPolicy::getWs, workspace)
                .eq(GovLcPolicy::getDeleteFlag, NOT_DELETE)
                .eq(GovLcPolicy::getStatus, "active")
                .orderByAsc(GovLcPolicy::getTableFqn));

        GovLcRun run = new GovLcRun();
        run.setId(runId);
        run.setRevision(1);
        run.setStatus("queued");
        run.setWs(workspace);
        run.setKind("orphan");
        run.setDryRun(true);
        run.setOperator(operator);
        run.setStartedAt(now);
        run.setDeleteFlag(NOT_DELETE);
        run.setCreateTime(now);
        run.setUpdateTime(now);

        // 全部 active 策略表：一批 Spark CALL remove_orphan_files(dry_run=>true)
        GovLcDsLauncher.LaunchResult launch = dsLauncher.launchOrphanDryRunBatch(workspace, policies, runId);
        if (!policies.isEmpty()) {
            run.setTableFqn(policies.get(0).getTableFqn());
        }

        run.setDsTaskId(launch.dsTaskId);
        run.setMetricsJson(JSONUtil.toJsonStr(launch.toMetricsJson()));
        if (launch.degraded && StrUtil.isBlank(launch.processInstanceId)) {
            run.setStatus("failed");
            run.setErrorMsg(launch.message);
            run.setFinishedAt(now);
        } else {
            run.setStatus(StrUtil.isNotBlank(launch.processInstanceId) ? "running" : "queued");
            if (!launch.degraded) {
                run.setFinishedAt(null);
            }
        }
        runMapper.insert(run);

        List<String> buckets = StrUtil.isNotBlank(param.getBucket())
                ? List.of(param.getBucket().trim())
                : List.of("iceberg-ods", "iceberg-dwd", "iceberg-dws");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String bucket : buckets) {
            GovLcOrphanScan scan = new GovLcOrphanScan();
            scan.setId(IdUtil.getSnowflakeNextIdStr());
            scan.setRevision(1);
            scan.setStatus(launch.degraded ? "failed" : "running");
            scan.setWs(workspace);
            scan.setBucket(bucket);
            long candidates = 0L;
            long bytes = 0L;
            boolean windowOk = !"iceberg-dws".equals(bucket);
            scan.setCandidateCount(candidates);
            scan.setBytes(bytes);
            scan.setWindowOk(windowOk);
            scan.setDryRun(true);
            scan.setResultUri("ds://" + StrUtil.blankToDefault(launch.processInstanceId, launch.workflowCode));
            scan.setRunId(runId);
            scan.setDeleteFlag(NOT_DELETE);
            scan.setCreateTime(now);
            scan.setUpdateTime(now);
            orphanScanMapper.insert(scan);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("scanId", scan.getId());
            row.put("bucket", bucket);
            row.put("candidateCount", candidates);
            row.put("bytes", bytes);
            row.put("windowOk", windowOk);
            row.put("status", launch.degraded ? "DS降级" : (windowOk ? "已提交dry-run，候选数待日志回写" : "等待安全窗"));
            row.put("resultUri", scan.getResultUri());
            rows.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("dryRun", true);
        out.put("dsTaskId", run.getDsTaskId());
        out.put("processInstanceId", launch.processInstanceId);
        out.put("workflowCode", launch.workflowCode);
        out.put("degraded", launch.degraded);
        out.put("message", launch.message);
        out.put("buckets", rows);
        out.put("note", "older_than≥7d；相对 expire +72h 后才允许物理删；已提交 Spark remove_orphan_files(dry_run)");
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovLcRunVo runNow(GovLcRunNowParam param) {
        String workspace = wsOrDefault(param != null ? param.getWs() : null);
        String operator = currentOperator();
        Date now = new Date();
        String batchId = IdUtil.getSnowflakeNextIdStr();
        String runId = IdUtil.getSnowflakeNextIdStr();

        List<GovLcPolicy> policies = policyMapper.selectList(new QueryWrapper<GovLcPolicy>().lambda()
                .eq(StrUtil.isNotBlank(workspace), GovLcPolicy::getWs, workspace)
                .eq(GovLcPolicy::getDeleteFlag, NOT_DELETE)
                .eq(GovLcPolicy::getStatus, "active"));

        GovLcDsLauncher.LaunchResult launch = dsLauncher.launchDaily(workspace, policies, runId, batchId);

        int stepNo = 1;
        for (String[] def : DAILY_STEPS) {
            GovLcJobStep step = new GovLcJobStep();
            step.setId(IdUtil.getSnowflakeNextIdStr());
            step.setRevision(1);
            step.setStatus(launch.degraded && StrUtil.isBlank(launch.processInstanceId) ? "failed" : "running");
            step.setWs(workspace);
            step.setBatchId(batchId);
            step.setStepNo(stepNo);
            step.setStepName(def[0]);
            step.setStepDesc(def[1]);
            step.setDetail(def[2] + (launch.degraded ? " · " + launch.message : " · DS " + launch.dsTaskId));
            step.setDurationSec(null);
            step.setDeleteFlag(NOT_DELETE);
            step.setCreateTime(now);
            step.setUpdateTime(now);
            jobStepMapper.insert(step);
            stepNo++;
        }

        GovLcRun run = new GovLcRun();
        run.setId(runId);
        run.setRevision(1);
        run.setStatus(StrUtil.isNotBlank(launch.processInstanceId) ? "running"
                : (launch.degraded ? "failed" : "queued"));
        run.setWs(workspace);
        run.setKind("daily");
        run.setBatchId(batchId);
        run.setDsTaskId(launch.dsTaskId);
        run.setDryRun(false);
        run.setOperator(operator);
        run.setStartedAt(now);
        run.setFinishedAt("failed".equals(run.getStatus()) ? now : null);
        run.setRemark(param != null ? param.getRemark() : null);
        run.setErrorMsg("failed".equals(run.getStatus()) ? launch.message : null);
        Map<String, Object> metrics = launch.toMetricsJson();
        metrics.put("steps", 4);
        metrics.put("order", List.of("expire", "rewrite", "orphan_dry_run", "partition"));
        metrics.put("policyCount", policies.size());
        run.setMetricsJson(JSONUtil.toJsonStr(metrics));
        run.setDeleteFlag(NOT_DELETE);
        run.setCreateTime(now);
        run.setUpdateTime(now);
        runMapper.insert(run);
        return toRunVo(run);
    }

    @Override
    public Map<String, Object> storageTrend(String ws, Integer days) {
        // 兼容旧调用：委托存储趋势分册服务（完整契约见 /lh/lifecycle/storage/*）
        String range = days == null || days <= 0 ? "30d" : days + "d";
        return govLcStorageService.trend(ws, range, "layer");
    }

    @Override
    public Page<GovLcRunVo> pageRuns(GovLcRunPageParam param) {
        String workspace = wsOrDefault(param.getWs());
        QueryWrapper<GovLcRun> qw = new QueryWrapper<GovLcRun>().checkSqlInjection();
        qw.lambda().eq(StrUtil.isNotBlank(workspace), GovLcRun::getWs, workspace).eq(GovLcRun::getDeleteFlag, NOT_DELETE);
        if (StrUtil.isNotBlank(param.getKind())) {
            qw.lambda().eq(GovLcRun::getKind, param.getKind().trim());
        }
        if (StrUtil.isNotBlank(param.getTableFqn())) {
            qw.lambda().eq(GovLcRun::getTableFqn, param.getTableFqn().trim());
        }
        if (StrUtil.isNotBlank(param.getStatus())) {
            qw.lambda().eq(GovLcRun::getStatus, param.getStatus().trim());
        }
        qw.lambda().orderByDesc(GovLcRun::getCreateTime);
        Page<GovLcRun> raw = runMapper.selectPage(CommonPageRequest.defaultPage(), qw);
        Page<GovLcRunVo> out = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        out.setRecords(raw.getRecords().stream().map(this::toRunVo).collect(Collectors.toList()));
        return out;
    }

    private GovLcRunVo enqueueTableAction(GovLcTableActionParam param, String kind) {
        String workspace = wsOrDefault(param.getWs());
        String fqn = param.getTableFqn().trim();
        assertComplianceOverride(param, kind);
        GovLcPolicy policy = findOrDefaultPolicy(workspace, fqn);
        String operator = currentOperator();
        Date now = new Date();
        String runId = IdUtil.getSnowflakeNextIdStr();

        Integer retainLast = "expire".equals(kind) ? param.getRetainLast() : null;
        GovLcDsLauncher.LaunchResult launch = dsLauncher.launchTableAction(
                kind, fqn, policy, runId, false, retainLast);

        GovLcRun run = new GovLcRun();
        run.setId(runId);
        run.setRevision(1);
        run.setWs(workspace);
        run.setKind(kind);
        run.setTableFqn(fqn);
        run.setDsTaskId(launch.dsTaskId);
        run.setDryRun(false);
        run.setOperator(operator);
        run.setRemark(param.getRemark());
        run.setReqNo(StrUtil.trimToNull(param.getReqNo()));
        run.setRetainLast(retainLast);
        run.setStartedAt(now);
        run.setMetricsJson(JSONUtil.toJsonStr(launch.toMetricsJson()));
        run.setDeleteFlag(NOT_DELETE);
        run.setCreateTime(now);
        run.setUpdateTime(now);
        if (StrUtil.isNotBlank(launch.processInstanceId)) {
            run.setStatus("running");
        } else if (launch.degraded) {
            run.setStatus("failed");
            run.setErrorMsg(launch.message);
            run.setFinishedAt(now);
        } else {
            run.setStatus("queued");
        }
        runMapper.insert(run);
        // 建议闭环：open → linked + linked_run_id
        try {
            String linkedAdviceId = adviceWriter.linkToRun(
                    param.getAdviceId(), workspace, fqn, kind, runId);
            if (StrUtil.isNotBlank(linkedAdviceId)) {
                Map<String, Object> metrics = StrUtil.isNotBlank(run.getMetricsJson())
                        ? new LinkedHashMap<>(JSONUtil.parseObj(run.getMetricsJson()))
                        : new LinkedHashMap<>();
                metrics.put("adviceId", linkedAdviceId);
                run.setMetricsJson(JSONUtil.toJsonStr(metrics));
                runMapper.updateById(run);
            }
        } catch (Exception ignored) {
            // 建议回写失败不阻断主路径
        }
        return toRunVo(run);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovLcRunVo syncRun(String runId) {
        if (StrUtil.isBlank(runId)) {
            throw new CommonException("runId 不能为空");
        }
        GovLcRun run = runMapper.selectById(runId.trim());
        if (run == null || !NOT_DELETE.equals(run.getDeleteFlag())) {
            throw new CommonException("运行记录不存在：" + runId);
        }
        String dsId = resolveProcessInstanceId(run);
        if (StrUtil.isBlank(dsId)) {
            if ("success".equals(run.getStatus())) {
                try {
                    adviceWriter.markDoneByRunId(run.getId());
                } catch (Exception ignored) {
                    /* ignore */
                }
            }
            return toRunVo(run);
        }
        Map<String, Object> inst = dsLauncher.syncInstance(dsId);
        String mapped = StrUtil.blankToDefault(String.valueOf(inst.get("mappedStatus")), run.getStatus());
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("dsState", inst.get("state"));
        extras.put("syncedAt", new Date().toString());
        extras.put("syncSource", "pull");
        if ("failed".equals(mapped)) {
            extras.put("errorHint", StrUtil.blankToDefault(String.valueOf(inst.get("message")),
                    "DS state=" + inst.get("state")));
        }
        return applyMappedStatus(run, mapped, extras, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovLcRunVo applyRunCallback(Map<String, Object> body) {
        if (body == null || StrUtil.isBlank(str(body.get("runId"), null))) {
            throw new CommonException("runId 必填");
        }
        String runId = str(body.get("runId"), null).trim();
        GovLcRun run = runMapper.selectById(runId);
        if (run == null || !NOT_DELETE.equals(run.getDeleteFlag())) {
            throw new CommonException("运行记录不存在：" + runId);
        }
        String instanceId = str(body.get("processInstanceId"), null);
        if (StrUtil.isNotBlank(instanceId)) {
            run.setDsTaskId(instanceId.trim());
        }
        // 可选步骤回写（日作业 gov_lc_job_step.step_name）
        Object stepsObj = body.get("steps");
        if (stepsObj instanceof List<?> list && StrUtil.isNotBlank(run.getBatchId())) {
            Date now = new Date();
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> raw)) {
                    continue;
                }
                String stepName = str(raw.get("stepName"), str(raw.get("nodeKey"), null));
                String st = str(raw.get("status"), null);
                if (StrUtil.isBlank(stepName) || StrUtil.isBlank(st)) {
                    continue;
                }
                List<GovLcJobStep> found = jobStepMapper.selectList(new QueryWrapper<GovLcJobStep>().lambda()
                        .eq(GovLcJobStep::getBatchId, run.getBatchId())
                        .eq(GovLcJobStep::getStepName, stepName.trim())
                        .eq(GovLcJobStep::getDeleteFlag, NOT_DELETE));
                for (GovLcJobStep s : found) {
                    s.setStatus(st.trim().toLowerCase(Locale.ROOT));
                    s.setUpdateTime(now);
                    if (raw.get("message") != null) {
                        s.setDetail(StrUtil.maxLength(String.valueOf(raw.get("message")), 500));
                    }
                    jobStepMapper.updateById(s);
                }
            }
        }
        String status = str(body.get("status"), null);
        if (StrUtil.isBlank(status) && body.get("dsState") != null) {
            status = GovLcDsLauncher.mapDsState(String.valueOf(body.get("dsState")));
        }
        if (StrUtil.isBlank(status)) {
            // 无显式状态：有实例则回落主动拉；否则原样返回
            String dsId = resolveProcessInstanceId(run);
            if (StrUtil.isNotBlank(dsId)) {
                return syncRun(runId);
            }
            return toRunVo(run);
        }
        String mapped = normalizeRunStatus(status);
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("callbackAt", new Date().toString());
        extras.put("syncSource", "push");
        if (body.get("dsState") != null) {
            extras.put("dsState", body.get("dsState"));
        }
        if (body.get("source") != null) {
            extras.put("callbackSource", body.get("source"));
        }
        if ("failed".equals(mapped) && body.get("message") != null) {
            extras.put("errorHint", String.valueOf(body.get("message")));
        }
        return applyMappedStatus(run, mapped, extras, str(body.get("message"), null));
    }

    private String resolveProcessInstanceId(GovLcRun run) {
        String dsId = run.getDsTaskId();
        if (StrUtil.isBlank(dsId) || dsId.startsWith("ds-stub-") || dsId.startsWith("wf:")) {
            Map<?, ?> metrics = StrUtil.isNotBlank(run.getMetricsJson())
                    ? JSONUtil.parseObj(run.getMetricsJson())
                    : Map.of();
            Object pid = metrics.get("processInstanceId");
            if (pid != null && StrUtil.isNotBlank(String.valueOf(pid))) {
                return String.valueOf(pid);
            }
            return null;
        }
        return dsId;
    }

    private static String normalizeRunStatus(String status) {
        String s = StrUtil.blankToDefault(status, "").trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "success", "succeeded", "finished", "complete", "completed", "ok" -> "success";
            case "failed", "fail", "failure", "killed", "stopped", "error" -> "failed";
            case "running", "executing" -> "running";
            case "queued", "pending", "submitted" -> "queued";
            default -> GovLcDsLauncher.mapDsState(status);
        };
    }

    /**
     * 统一写回 run 状态 + 终态副作用（effect / advice done / 日作业步骤）。
     */
    private GovLcRunVo applyMappedStatus(
            GovLcRun run, String mapped, Map<String, Object> extras, String errorMsg) {
        Map<String, Object> metrics = StrUtil.isNotBlank(run.getMetricsJson())
                ? new LinkedHashMap<>(JSONUtil.parseObj(run.getMetricsJson()))
                : new LinkedHashMap<>();
        if (extras != null) {
            metrics.putAll(extras);
        }
        boolean changed = !mapped.equals(run.getStatus());
        if (changed) {
            run.setStatus(mapped);
            run.setRevision(nvlInt(run.getRevision()) + 1);
            run.setUpdateTime(new Date());
            if ("success".equals(mapped) || "failed".equals(mapped)) {
                run.setFinishedAt(new Date());
            }
            if ("failed".equals(mapped)) {
                run.setErrorMsg(StrUtil.blankToDefault(errorMsg,
                        StrUtil.blankToDefault(str(extras != null ? extras.get("errorHint") : null, null),
                                "callback/sync failed")));
            }
            if (StrUtil.isNotBlank(run.getBatchId()) && ("success".equals(mapped) || "failed".equals(mapped))) {
                List<GovLcJobStep> steps = jobStepMapper.selectList(new QueryWrapper<GovLcJobStep>().lambda()
                        .eq(GovLcJobStep::getBatchId, run.getBatchId())
                        .eq(GovLcJobStep::getDeleteFlag, NOT_DELETE));
                for (GovLcJobStep s : steps) {
                    s.setStatus(mapped);
                    s.setUpdateTime(new Date());
                    jobStepMapper.updateById(s);
                }
            }
        }
        if ("success".equals(mapped) && !Boolean.TRUE.equals(metrics.get("effectApplied"))) {
            run.setStatus("success");
            try {
                metrics.put("effect", effectWriter.apply(run));
                metrics.put("effectApplied", true);
            } catch (Exception e) {
                metrics.put("effectError", e.getMessage());
            }
            if (!changed) {
                run.setRevision(nvlInt(run.getRevision()) + 1);
            }
            changed = true;
        }
        if ("success".equals(mapped) && !Boolean.TRUE.equals(metrics.get("adviceDone"))) {
            try {
                int n = adviceWriter.markDoneByRunId(run.getId());
                metrics.put("adviceDone", true);
                metrics.put("adviceDoneCount", n);
                changed = true;
            } catch (Exception e) {
                metrics.put("adviceDoneError", e.getMessage());
                changed = true;
            }
        }
        if (changed || (extras != null && !extras.isEmpty())) {
            run.setMetricsJson(JSONUtil.toJsonStr(metrics));
            run.setUpdateTime(new Date());
            runMapper.updateById(run);
        }
        return toRunVo(run);
    }

    private static String str(Object o, String dft) {
        if (o == null) {
            return dft;
        }
        String s = String.valueOf(o);
        return StrUtil.isBlank(s) || "null".equalsIgnoreCase(s) ? dft : s;
    }

    private void assertComplianceOverride(GovLcTableActionParam param, String kind) {
        boolean hasReq = StrUtil.isNotBlank(param.getReqNo());
        boolean hasRetain = param.getRetainLast() != null;
        boolean complianceDag = "compliance_delete".equals(kind);
        if (!hasReq && !hasRetain && !complianceDag) {
            return;
        }
        if (!hasReq) {
            throw new CommonException("retainLast 覆盖必须携带合规请求号 reqNo");
        }
        if (hasRetain && !"expire".equals(kind) && !complianceDag) {
            throw new CommonException("retainLast 只适用于快照过期或合规硬删 DAG");
        }
        if (hasRetain && param.getRetainLast() != 1) {
            throw new CommonException("合规定向过期仅允许 retainLast=1");
        }
        GovDelRequest req = govDelRequestMapper.selectOne(new QueryWrapper<GovDelRequest>().lambda()
                .eq(GovDelRequest::getReqNo, param.getReqNo().trim())
                .eq(GovDelRequest::getDeleteFlag, NOT_DELETE)
                .last("LIMIT 1"));
        if (req == null) {
            throw new CommonException("合规删除请求不存在: " + param.getReqNo());
        }
        if (!Set.of("executing", "verifying").contains(StrUtil.blankToDefault(req.getStatus(), ""))) {
            throw new CommonException("合规参数覆盖仅在工单 executing/verifying 时接受，当前 "
                    + req.getStatus());
        }
    }

    private GovLcPolicy findOrDefaultPolicy(String ws, String fqn) {
        GovLcPolicy p = findPolicy(ws, fqn);
        if (p != null) {
            return p;
        }
        // 允许对未建策略表触发动作：使用默认策略影子（不强制落库）
        GovLcPolicy shadow = new GovLcPolicy();
        shadow.setWs(ws);
        shadow.setTableFqn(fqn);
        shadow.setKeepCount(20);
        shadow.setKeepDays(7);
        shadow.setMinSnapshots(5);
        shadow.setCompactLevel("L2");
        shadow.setTargetFileMb(256);
        shadow.setOrphanOlderDays(7);
        shadow.setOrphanSafetyHours(72);
        shadow.setStatus("active");
        return shadow;
    }

    private void applyPolicyFields(GovLcPolicy p, GovLcPolicyUpsertParam param) {
        if (param.getKeepCount() != null) {
            p.setKeepCount(param.getKeepCount());
        } else if (p.getKeepCount() == null) {
            p.setKeepCount(20);
        }
        if (param.getKeepDays() != null) {
            p.setKeepDays(param.getKeepDays());
        } else if (p.getKeepDays() == null) {
            p.setKeepDays(7);
        }
        if (param.getMinSnapshots() != null) {
            p.setMinSnapshots(param.getMinSnapshots());
        } else if (p.getMinSnapshots() == null) {
            p.setMinSnapshots(5);
        }
        if (StrUtil.isNotBlank(param.getCompactLevel())) {
            p.setCompactLevel(param.getCompactLevel().trim().toUpperCase(Locale.ROOT));
        } else if (StrUtil.isBlank(p.getCompactLevel())) {
            p.setCompactLevel("L2");
        }
        if (param.getTargetFileMb() != null) {
            p.setTargetFileMb(param.getTargetFileMb());
        } else if (p.getTargetFileMb() == null) {
            p.setTargetFileMb(256);
        }
        if (param.getOrphanOlderDays() != null) {
            if (param.getOrphanOlderDays() < 7) {
                throw new CommonException("orphanOlderDays 默认不得低于 7");
            }
            p.setOrphanOlderDays(param.getOrphanOlderDays());
        } else if (p.getOrphanOlderDays() == null) {
            p.setOrphanOlderDays(7);
        }
        if (param.getOrphanSafetyHours() != null) {
            if (param.getOrphanSafetyHours() < 72) {
                throw new CommonException("orphanSafetyHours 默认不得低于 72");
            }
            p.setOrphanSafetyHours(param.getOrphanSafetyHours());
        } else if (p.getOrphanSafetyHours() == null) {
            p.setOrphanSafetyHours(72);
        }
        if (param.getPartitionExpireDays() != null) {
            p.setPartitionExpireDays(param.getPartitionExpireDays());
        }
        if (StrUtil.isNotBlank(param.getLayer())) {
            p.setLayer(param.getLayer().trim().toUpperCase(Locale.ROOT));
        }
        if (param.getOwner() != null) {
            p.setOwner(param.getOwner());
        }
        if (param.getRemark() != null) {
            p.setRemark(param.getRemark());
        }
    }

    private GovLcPolicy findPolicy(String ws, String fqn) {
        return policyMapper.selectOne(new QueryWrapper<GovLcPolicy>().lambda()
                .eq(GovLcPolicy::getWs, ws)
                .eq(GovLcPolicy::getTableFqn, fqn)
                .eq(GovLcPolicy::getDeleteFlag, NOT_DELETE)
                .last("LIMIT 1"));
    }

    private List<GovLcTableStat> listStats(String ws) {
        return tableStatMapper.selectList(new QueryWrapper<GovLcTableStat>().lambda()
                .eq(GovLcTableStat::getWs, ws)
                .eq(GovLcTableStat::getDeleteFlag, NOT_DELETE));
    }

    private Map<String, Object> statToMap(GovLcTableStat s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tableFqn", s.getTableFqn());
        m.put("layer", s.getLayer());
        m.put("sizeBytes", nvl(s.getSizeBytes()));
        m.put("fileCount", nvl(s.getFileCount()));
        m.put("avgFileBytes", nvl(s.getAvgFileBytes()));
        m.put("growth7dPct", s.getGrowth7dPct());
        m.put("snapshotCount", s.getSnapshotCount());
        m.put("policyLabel", s.getPolicyLabel());
        m.put("status", s.getStatus());
        m.put("collectedAt", s.getCollectedAt());
        return m;
    }

    private GovLcPolicyVo toPolicyVo(GovLcPolicy p) {
        GovLcPolicyVo vo = new GovLcPolicyVo();
        vo.setId(p.getId());
        vo.setWs(p.getWs());
        vo.setStatus(p.getStatus());
        vo.setTableFqn(p.getTableFqn());
        vo.setKeepCount(p.getKeepCount());
        vo.setKeepDays(p.getKeepDays());
        vo.setMinSnapshots(p.getMinSnapshots());
        vo.setCompactLevel(p.getCompactLevel());
        vo.setTargetFileMb(p.getTargetFileMb());
        vo.setOrphanOlderDays(p.getOrphanOlderDays());
        vo.setOrphanSafetyHours(p.getOrphanSafetyHours());
        vo.setPartitionExpireDays(p.getPartitionExpireDays());
        vo.setLayer(p.getLayer());
        vo.setOwner(p.getOwner());
        vo.setRemark(p.getRemark());
        vo.setUpdateTime(p.getUpdateTime());
        return vo;
    }

    private GovLcRunVo toRunVo(GovLcRun run) {
        GovLcRunVo vo = new GovLcRunVo();
        vo.setRunId(run.getId());
        vo.setWs(run.getWs());
        vo.setKind(run.getKind());
        vo.setTableFqn(run.getTableFqn());
        vo.setBatchId(run.getBatchId());
        vo.setStatus(run.getStatus());
        vo.setDsTaskId(run.getDsTaskId());
        vo.setDryRun(run.getDryRun());
        vo.setMetricsJson(run.getMetricsJson());
        vo.setErrorMsg(run.getErrorMsg());
        vo.setOperator(run.getOperator());
        vo.setReqNo(run.getReqNo());
        vo.setRetainLast(run.getRetainLast());
        vo.setStartedAt(run.getStartedAt());
        vo.setFinishedAt(run.getFinishedAt());
        vo.setCreateTime(run.getCreateTime());
        return vo;
    }

    private String currentOperator() {
        try {
            SaBaseLoginUser login = StpLoginUserUtil.getLoginUser();
            if (login != null) {
                if (StrUtil.isNotBlank(login.getName())) {
                    return login.getName();
                }
                if (StrUtil.isNotBlank(login.getAccount())) {
                    return login.getAccount();
                }
                return String.valueOf(login.getId());
            }
        } catch (Exception ignored) {
            // 未登录/测试场景
        }
        return "system";
    }

    private String wsOrDefault(String ws) {
        return StrUtil.blankToDefault(ws, WS_DEFAULT);
    }

    /** 列表：空=全局 */
    private String listWs(String ws) {
        return vip.xiaonuo.lh.core.ws.LhWsFilters.listWs(ws);
    }

    private long nvl(Long v) {
        return v == null ? 0L : v;
    }

    private int nvlInt(Integer v) {
        return v == null ? 0 : v;
    }

    private double bytesToTb(long bytes) {
        if (bytes <= 0) {
            return 0;
        }
        return BigDecimal.valueOf(bytes / (1024.0 * 1024 * 1024 * 1024)).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private long toLong(Object v) {
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    private Date monthStart() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.DAY_OF_MONTH, 1);
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        return cal.getTime();
    }

    private long sumCleanedBytes(List<GovLcRun> monthRuns) {
        long sum = 0L;
        if (monthRuns == null) {
            return 0L;
        }
        for (GovLcRun r : monthRuns) {
            if (StrUtil.isBlank(r.getMetricsJson())) {
                continue;
            }
            try {
                cn.hutool.json.JSONObject o = JSONUtil.parseObj(r.getMetricsJson());
                for (String key : List.of("cleanedBytes", "reclaimedBytes", "deletedBytes", "bytesFreed", "effectBytes")) {
                    if (o.containsKey(key) && o.get(key) instanceof Number n) {
                        sum += n.longValue();
                        break;
                    }
                }
                if (o.get("effect") instanceof cn.hutool.json.JSONObject eff) {
                    for (String key : List.of("cleanedBytes", "reclaimedBytes", "bytes")) {
                        if (eff.containsKey(key) && eff.get(key) instanceof Number n) {
                            sum += n.longValue();
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {
                // ignore malformed metrics
            }
        }
        return sum;
    }

    private long countCompliancePending(String workspace) {
        Set<String> open = Set.of(
                "assessing", "pending_approval", "scheduled", "executing", "verifying",
                "partial_failed", "on_hold");
        Long n = govDelRequestMapper.selectCount(new QueryWrapper<GovDelRequest>().lambda()
                .eq(StrUtil.isNotBlank(workspace), GovDelRequest::getWs, workspace)
                .eq(GovDelRequest::getDeleteFlag, NOT_DELETE)
                .in(GovDelRequest::getStatus, open));
        return n == null ? 0L : n;
    }

    private Map<String, Object> tierBytesFromBuckets(String workspace) {
        Map<String, Object> bucketsPayload = govLcStorageService.buckets(workspace);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> buckets = bucketsPayload.get("list") instanceof List<?> raw
                ? (List<Map<String, Object>>) raw
                : List.of();
        long hot = 0;
        long warm = 0;
        long cold = 0;
        for (Map<String, Object> b : buckets) {
            long used = toLong(b.get("usedBytes"));
            String tier = String.valueOf(b.getOrDefault("tier", "warm")).toLowerCase(Locale.ROOT);
            if ("hot".equals(tier)) {
                hot += used;
            } else if ("cold".equals(tier)) {
                cold += used;
            } else {
                warm += used;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hotTb", bytesToTb(hot));
        m.put("warmTb", bytesToTb(warm));
        m.put("coldTb", bytesToTb(cold));
        m.put("hotBytes", hot);
        m.put("warmBytes", warm);
        m.put("coldBytes", cold);
        m.put("source", bucketsPayload.getOrDefault("source", buckets.isEmpty() ? "empty" : "buckets"));
        return m;
    }

    private static String statusApprovalLabel(String status) {
        return switch (StrUtil.blankToDefault(status, "")) {
            case "pending_approval" -> "安全/法务/Owner";
            case "assessing" -> "评估中";
            case "scheduled" -> "已排期";
            case "executing", "verifying" -> "执行中";
            case "on_hold" -> "法务冻结";
            case "restricted" -> "限制处理";
            case "archived" -> "待销毁";
            default -> "—";
        };
    }

    private static String statusZh(String status) {
        return switch (StrUtil.blankToDefault(status, "")) {
            case "assessing" -> "评估中";
            case "pending_approval" -> "待审批";
            case "scheduled" -> "已排期";
            case "executing" -> "执行中";
            case "verifying" -> "验证中";
            case "partial_failed" -> "部分失败";
            case "on_hold" -> "法务冻结";
            case "restricted" -> "限制处理";
            case "archived" -> "待备份销毁";
            case "done" -> "已完成";
            default -> status;
        };
    }

    private static String statusCls(String status) {
        return switch (StrUtil.blankToDefault(status, "")) {
            case "pending_approval", "on_hold", "restricted" -> "tag-orange";
            case "executing", "verifying" -> "tag-purple";
            case "partial_failed" -> "tag-red";
            case "done" -> "tag-green";
            case "archived" -> "tag-gray";
            default -> "tag-blue";
        };
    }
}
