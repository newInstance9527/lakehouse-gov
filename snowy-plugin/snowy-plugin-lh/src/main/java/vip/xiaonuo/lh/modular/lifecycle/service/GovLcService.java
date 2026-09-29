package vip.xiaonuo.lh.modular.lifecycle.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import vip.xiaonuo.lh.modular.lifecycle.param.GovLcOrphanScanParam;
import vip.xiaonuo.lh.modular.lifecycle.param.GovLcPolicyUpsertParam;
import vip.xiaonuo.lh.modular.lifecycle.param.GovLcRunNowParam;
import vip.xiaonuo.lh.modular.lifecycle.param.GovLcRunPageParam;
import vip.xiaonuo.lh.modular.lifecycle.param.GovLcTableActionParam;
import vip.xiaonuo.lh.modular.lifecycle.result.GovLcPolicyVo;
import vip.xiaonuo.lh.modular.lifecycle.result.GovLcRunVo;

import java.util.List;
import java.util.Map;

/**
 * 生命周期与小文件治理（对齐 doc/生命周期.md P0）
 */
public interface GovLcService {

    Map<String, Object> overview(String ws);

    Map<String, Object> latestJobs(String ws);

    List<Map<String, Object>> topStorage(String ws, Integer limit);

    /** 分区归档候选（有 partition_expire_days 的策略表）；无策略时为空列表 */
    List<Map<String, Object>> archiveCandidates(String ws);

    /** 合规工单只读预览（深链 /compliance）；空列表合法 */
    List<Map<String, Object>> compliancePreview(String ws, Integer limit);

    Map<String, Object> stats(String ws, String tableFqn);

    List<GovLcPolicyVo> listPolicies(String ws);

    GovLcPolicyVo getPolicy(String ws, String tableFqn);

    GovLcPolicyVo upsertPolicy(GovLcPolicyUpsertParam param);

    GovLcRunVo compact(GovLcTableActionParam param);

    GovLcRunVo expire(GovLcTableActionParam param);

    /**
     * 合规 Iceberg 硬删独立 DAG：delete → compact → 定向 expire(retain_last=1)。
     * 模板名 {@code job.compliance.delete.iceberg}，不可复用日作业 DAG。
     */
    GovLcRunVo complianceDeleteIceberg(GovLcTableActionParam param);

    Map<String, Object> orphanScan(GovLcOrphanScanParam param);

    GovLcRunVo runNow(GovLcRunNowParam param);

    Map<String, Object> storageTrend(String ws, Integer days);

    Page<GovLcRunVo> pageRuns(GovLcRunPageParam param);

    /** 按 DS processInstanceId 回写 gov_lc_run 状态（门户主动拉） */
    GovLcRunVo syncRun(String runId);

    /**
     * DS/Worker 推送回调回写（J6）。
     * body: runId, status?, dsState?, processInstanceId?, message?, steps?[{stepName,status}]
     */
    GovLcRunVo applyRunCallback(Map<String, Object> body);
}
