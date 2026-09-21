package vip.xiaonuo.lh.modular.lifecycle.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.core.engine.DsClient;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcOrphanScan;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcPolicy;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcRun;
import vip.xiaonuo.lh.modular.lifecycle.mapper.GovLcOrphanScanMapper;
import vip.xiaonuo.lh.modular.lifecycle.mapper.GovLcPolicyMapper;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Procedure 成功后把文件数、均大小、孤儿候选数写回，而不是只留 DS 实例号。
 */
@Component
public class GovLcRunEffectWriter {

    private static final Logger log = LoggerFactory.getLogger(GovLcRunEffectWriter.class);
    private static final String NOT_DELETE = "NOT_DELETE";
    private static final int DAILY_PROFILE_LIMIT = 8;

    @Resource
    private GovLcStorageProfileCollector profileCollector;
    @Resource
    private GovLcOrphanScanMapper orphanScanMapper;
    @Resource
    private GovLcPolicyMapper policyMapper;
    @Resource
    private DsClient dsClient;

    public Map<String, Object> apply(GovLcRun run) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (run == null || !"success".equals(run.getStatus())) {
            out.put("skipped", true);
            return out;
        }
        String kind = StrUtil.blankToDefault(run.getKind(), "");
        try {
            if ("compact".equals(kind) || "expire".equals(kind) || "rewrite".equals(kind)) {
                if (StrUtil.isNotBlank(run.getTableFqn())) {
                    out.put("profile", profileCollector.refresh(run.getWs(), List.of(run.getTableFqn())));
                }
            } else if ("daily".equals(kind)) {
                List<String> fqns = policyFqns(run.getWs());
                if (fqns.size() > DAILY_PROFILE_LIMIT) {
                    fqns = fqns.subList(0, DAILY_PROFILE_LIMIT);
                    out.put("profileTruncated", true);
                }
                if (!fqns.isEmpty()) {
                    out.put("profile", profileCollector.refresh(run.getWs(), fqns));
                }
            }
        } catch (Exception e) {
            log.warn("lifecycle profile writeback failed run={}: {}", run.getId(), e.getMessage());
            out.put("profileError", e.getMessage());
        }
        if ("orphan".equals(kind) || "daily".equals(kind)) {
            out.put("orphan", writeOrphanCandidates(run));
        }
        return out;
    }

    private Map<String, Object> writeOrphanCandidates(GovLcRun run) {
        Map<String, Object> out = new LinkedHashMap<>();
        String instanceId = processInstanceId(run);
        long candidates = -1;
        String note = null;
        if (StrUtil.isBlank(instanceId)) {
            note = "无 DS 实例，孤儿候选未回写";
        } else {
            Map<String, Object> tasks = dsClient.listTaskInstances(instanceId);
            Object raw = tasks.get("tasks");
            StringBuilder logs = new StringBuilder();
            if (raw instanceof List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> task)) {
                        continue;
                    }
                    Object id = task.get("id");
                    if (id == null) {
                        continue;
                    }
                    Map<String, Object> logPage = dsClient.queryTaskInstanceLog(String.valueOf(id), 0, 2000);
                    Object message = logPage.get("message");
                    if (message != null) {
                        logs.append(message).append('\n');
                    }
                }
            }
            candidates = GovLcOrphanLogParser.countCandidates(logs.toString());
            if (candidates < 0) {
                note = "日志未解析到孤儿候选，保留 0，不用种子数";
                candidates = 0;
            }
        }
        List<GovLcOrphanScan> scans = orphanScanMapper.selectList(new QueryWrapper<GovLcOrphanScan>().lambda()
                .eq(GovLcOrphanScan::getRunId, run.getId())
                .eq(GovLcOrphanScan::getDeleteFlag, NOT_DELETE)
                .orderByAsc(GovLcOrphanScan::getCreateTime));
        boolean first = true;
        for (GovLcOrphanScan scan : scans) {
            scan.setStatus("done");
            scan.setUpdateTime(new Date());
            if (first) {
                scan.setCandidateCount(Math.max(0, candidates));
                scan.setBytes(0L);
                scan.setRemark(note);
                first = false;
            } else {
                scan.setCandidateCount(0L);
                scan.setBytes(0L);
                scan.setRemark("合计见同 run 首行，避免按桶加总重复");
            }
            orphanScanMapper.updateById(scan);
        }
        out.put("candidateCount", Math.max(0, candidates));
        out.put("parsed", note == null);
        out.put("note", note);
        out.put("processInstanceId", instanceId);
        return out;
    }

    private String processInstanceId(GovLcRun run) {
        String ds = run.getDsTaskId();
        if (StrUtil.isNotBlank(ds) && !ds.startsWith("wf:") && !ds.startsWith("ds-stub-")) {
            return ds;
        }
        if (StrUtil.isBlank(run.getMetricsJson())) {
            return null;
        }
        Object pid = JSONUtil.parseObj(run.getMetricsJson()).get("processInstanceId");
        return pid == null ? null : String.valueOf(pid);
    }

    private List<String> policyFqns(String ws) {
        List<GovLcPolicy> policies = policyMapper.selectList(new QueryWrapper<GovLcPolicy>().lambda()
                .eq(GovLcPolicy::getWs, ws)
                .eq(GovLcPolicy::getDeleteFlag, NOT_DELETE)
                .eq(GovLcPolicy::getStatus, "active"));
        List<String> fqns = new ArrayList<>();
        for (GovLcPolicy p : policies) {
            if (StrUtil.isNotBlank(p.getTableFqn())) {
                fqns.add(p.getTableFqn());
            }
        }
        return fqns;
    }
}
