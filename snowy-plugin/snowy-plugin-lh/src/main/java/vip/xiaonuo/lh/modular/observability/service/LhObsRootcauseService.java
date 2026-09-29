package vip.xiaonuo.lh.modular.observability.service;

import java.util.Map;

/**
 * 根因分析门面（§6.8）：无证据时合法空态；禁止演示故事线。
 */
public interface LhObsRootcauseService {

    /** 告警焦点候选项（无真实告警 → 空列表） */
    Map<String, Object> alerts(String ws);

    /**
     * 最小编排：输入告警/表/作业/时间窗 → 步骤 + 证据 + 建议。
     * 无证据时 steps/evidence/actions 为空。
     */
    Map<String, Object> analyze(Map<String, Object> body);

    /** 结论落库/回写 + AI diagnose 钩子（不伪造证据） */
    Map<String, Object> conclusion(Map<String, Object> body);

    /** 一键处置：ETL backfill + 工单提示 */
    Map<String, Object> remediate(Map<String, Object> body);
}
