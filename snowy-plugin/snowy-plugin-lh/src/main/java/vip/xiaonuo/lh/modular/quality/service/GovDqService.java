package vip.xiaonuo.lh.modular.quality.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import vip.xiaonuo.lh.modular.quality.param.GovDqEvaluateParam;
import vip.xiaonuo.lh.modular.quality.param.GovDqGateUpsertParam;
import vip.xiaonuo.lh.modular.quality.param.GovDqIdParam;
import vip.xiaonuo.lh.modular.quality.param.GovDqPageParam;
import vip.xiaonuo.lh.modular.quality.param.GovDqRunAddParam;
import vip.xiaonuo.lh.modular.quality.param.GovDqRuleUpsertParam;
import vip.xiaonuo.lh.modular.quality.param.GovDqStreamProbeParam;
import vip.xiaonuo.lh.modular.quality.result.GovDqRuleVo;

import java.util.List;
import java.util.Map;

public interface GovDqService {
    Map<String, Object> overview(String ws, String range);

    List<Map<String, Object>> trend(String ws, String range);

    List<Map<String, Object>> typeDist(String ws);

    List<Map<String, Object>> gold(String ws, Integer limit);

    Page<GovDqRuleVo> pageRules(GovDqPageParam param);

    GovDqRuleVo upsertRule(GovDqRuleUpsertParam param);

    void deleteRule(GovDqIdParam param);

    Page<Map<String, Object>> pageRuns(String ruleId, String ws);

    Map<String, Object> addRun(GovDqRunAddParam param);

    /**
     * 质量节点批量裁决：无 results 时 Trino/JDBC 真探数写 {@code gov_dq_rule_run}，
     * 返回 {@code blocked} 供 DS exit 1 / 试跑阻断。
     */
    Map<String, Object> evaluate(GovDqEvaluateParam param);

    /**
     * 发布门禁：读 {@code gov_dq_gate}，对比表/层近跑分数。
     *
     * @return status=pass|fail|skip + detail
     */
    Map<String, Object> assessPublishGate(String ws, String tableHint, String layerHint);

    List<Map<String, Object>> listGates(String ws);

    Map<String, Object> upsertGate(GovDqGateUpsertParam param);

    void deleteGate(GovDqIdParam param);

    Map<String, Object> createTicket(String ruleId, String remark);

    Map<String, Object> syncOm(String ws);

    /**
     * Flink 流式探针回调：写 VM {@code lh_dq_stream_*}（夜莺）；可选落 run；不阻断 DAG。
     */
    Map<String, Object> streamProbe(GovDqStreamProbeParam param);
}
