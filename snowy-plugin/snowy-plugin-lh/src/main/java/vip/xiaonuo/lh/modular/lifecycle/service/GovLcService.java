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

    Map<String, Object> stats(String ws, String tableFqn);

    List<GovLcPolicyVo> listPolicies(String ws);

    GovLcPolicyVo getPolicy(String ws, String tableFqn);

    GovLcPolicyVo upsertPolicy(GovLcPolicyUpsertParam param);

    GovLcRunVo compact(GovLcTableActionParam param);

    GovLcRunVo expire(GovLcTableActionParam param);

    Map<String, Object> orphanScan(GovLcOrphanScanParam param);

    GovLcRunVo runNow(GovLcRunNowParam param);

    Map<String, Object> storageTrend(String ws, Integer days);

    Page<GovLcRunVo> pageRuns(GovLcRunPageParam param);

    /** 按 DS processInstanceId 回写 gov_lc_run 状态 */
    GovLcRunVo syncRun(String runId);
}
