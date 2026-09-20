package vip.xiaonuo.lh.modular.quality.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import vip.xiaonuo.lh.modular.quality.param.GovDqGateUpsertParam;
import vip.xiaonuo.lh.modular.quality.param.GovDqIdParam;
import vip.xiaonuo.lh.modular.quality.param.GovDqPageParam;
import vip.xiaonuo.lh.modular.quality.param.GovDqRunAddParam;
import vip.xiaonuo.lh.modular.quality.param.GovDqRuleUpsertParam;
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

    List<Map<String, Object>> listGates(String ws);

    Map<String, Object> upsertGate(GovDqGateUpsertParam param);

    Map<String, Object> createTicket(String ruleId, String remark);

    Map<String, Object> syncOm(String ws);
}
