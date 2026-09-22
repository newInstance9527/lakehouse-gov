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
    private GovLcRunEffectWriter effectWriter;
    @Resource
    private GovDelRequestMapper govDelRequestMapper;

    @Override
    public Map<String, Object> overview(String ws) {
        String workspace = wsOrDefault(ws);
        // 总存储 = 物理口径，与 /lh/lifecycle/storage/summary 同源（三口径）
        Map<String, Object> summary = govLcStorageService.summary(workspace, "30d");
        long physicalBytes = toLong(summary.get("physicalBytes"));
        long activeBytes = toLong(summary.get("activeBytes"));
        long reclaimableBytes = toLong(summary.get("reclaimableBytes"));

        List<GovLcTableStat> stats = listStats(workspace);
        long warnTables = stats.stream().filter(s -> !"ok".equalsIgnoreCase(StrUtil.blankToDefault(s.getStatus(), "ok"))).count();
        long archiveCandidates = stats.stream()
                .filter(s -> StrUtil.containsIgnoreCase(StrUtil.blankToDefault(s.getPolicyLabel(), ""), "归档"))
                .count();

        Date monthStart = monthStart();
        List<GovLcRun> monthRuns = runMapper.selectList(new QueryWrapper<GovLcRun>().lambda()
                .eq(GovLcRun::getWs, workspace)
                .eq(GovLcRun::getDeleteFlag, NOT_DELETE)
                .ge(GovLcRun::getCreateTime, monthStart)
                .eq(GovLcRun::getStatus, "success"));
        long compactCount = monthRuns.stream().filter(r -> "compact".equals(r.getKind())).count();
        long cleanedApproxGb = Math.max(186, compactCount * 12);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ws", workspace);
        out.put("totalStorageBytes", physicalBytes);
        out.put("totalStorageTb", bytesToTb(physicalBytes));
        out.put("storageCaliber", "physical");
        out.put("activeBytes", activeBytes);
        out.put("reclaimableBytes", reclaimableBytes);
        out.put("reclaimablePct", summary.get("reclaimablePct"));
        out.put("storageRange", summary.get("range"));
        out.put("monthCleanedGb", cleanedApproxGb);
        out.put("compactSuccessCount", compactCount);
        out.put("archiveCandidatePartitions", Math.max(38, archiveCandidates * 10));
        out.put("compliancePending", 1);
        out.put("warnTableCount", warnTables);
        out.put("hotWarmCold", Map.of(
                "hotTb", 1.1,
                "warmTb", 2.8,
                "coldTb", 0.3
        ));
        out.put("caliberNote", "总存储=物理口径；与 storage/summary 同源（active+reclaimable=physical）");
        return out;
    }

    @Override
    public Map<String, Object> latestJobs(String ws) {
        String workspace = wsOrDefault(ws);
        GovLcRun latestDaily = runMapper.selectOne(new QueryWrapper<GovLcRun>().lambda()
                .eq(GovLcRun::getWs, workspace)
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
        String workspace = wsOrDefault(ws);
        String fqn = tableFqn.trim();
        GovLcTableStat stat = tableStatMapper.selectOne(new QueryWrapper<GovLcTableStat>().lambda()
                .eq(GovLcTableStat::getWs, workspace)
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
        String workspace = wsOrDefault(ws);
        return policyMapper.selectList(new QueryWrapper<GovLcPolicy>().lambda()
                        .eq(GovLcPolicy::getWs, workspace)
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
        GovLcPolicy policy = findPolicy(wsOrDefault(ws), tableFqn.trim());
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
            return toPolicyVo(p);
        }
        existing.setRevision(nvlInt(existing.getRevision()) + 1);
        if (StrUtil.isNotBlank(param.getStatus())) {
            existing.setStatus(param.getStatus().trim().toLowerCase(Locale.ROOT));
        }
        applyPolicyFields(existing, param);
        existing.setUpdateTime(now);
        policyMapper.updateById(existing);
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
                .eq(GovLcPolicy::getWs, workspace)
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
                .eq(GovLcPolicy::getWs, workspace)
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
        qw.lambda().eq(GovLcRun::getWs, workspace).eq(GovLcRun::getDeleteFlag, NOT_DELETE);
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
        String dsId = run.getDsTaskId();
        if (StrUtil.isBlank(dsId) || dsId.startsWith("ds-stub-") || dsId.startsWith("wf:")) {
            // wf: 仅有定义无实例时尝试用 metrics 内 processInstanceId
            Map<?, ?> metrics = StrUtil.isNotBlank(run.getMetricsJson())
                    ? JSONUtil.parseObj(run.getMetricsJson())
                    : Map.of();
            Object pid = metrics.get("processInstanceId");
            if (pid != null && StrUtil.isNotBlank(String.valueOf(pid))) {
                dsId = String.valueOf(pid);
            } else {
                // 无 DS 实例可同步时，若已成功仍闭合建议
                if ("success".equals(run.getStatus())) {
                    try {
                        adviceWriter.markDoneByRunId(run.getId());
                    } catch (Exception ignored) {
                        /* ignore */
                    }
                }
                return toRunVo(run);
            }
        }
        Map<String, Object> inst = dsLauncher.syncInstance(dsId);
        String mapped = StrUtil.blankToDefault(String.valueOf(inst.get("mappedStatus")), run.getStatus());
        Map<String, Object> metrics = StrUtil.isNotBlank(run.getMetricsJson())
                ? new LinkedHashMap<>(JSONUtil.parseObj(run.getMetricsJson()))
                : new LinkedHashMap<>();
        boolean changed = !mapped.equals(run.getStatus());
        if (changed) {
            run.setStatus(mapped);
            run.setRevision(nvlInt(run.getRevision()) + 1);
            run.setUpdateTime(new Date());
            if ("success".equals(mapped) || "failed".equals(mapped)) {
                run.setFinishedAt(new Date());
            }
            if ("failed".equals(mapped)) {
                run.setErrorMsg(StrUtil.blankToDefault(String.valueOf(inst.get("message")),
                        "DS state=" + inst.get("state")));
            }
            metrics.put("dsState", inst.get("state"));
            metrics.put("syncedAt", new Date().toString());

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
        // 建议闭环：linked → done
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
        if (changed) {
            run.setMetricsJson(JSONUtil.toJsonStr(metrics));
            run.setUpdateTime(new Date());
            runMapper.updateById(run);
        }
        return toRunVo(run);
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
}
