package vip.xiaonuo.lh.modular.quality.param;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * 质量节点批量裁决（DS SHELL / 试跑 / 发布共用）。
 * <p>有 {@link #results} 时以作业上报为准；否则按规则 enabled / severity stub 裁决（本期无引擎实探）。</p>
 */
@Getter
@Setter
public class GovDqEvaluateParam {
    private String ws;
    private String jobRunId;
    private String nodeKey;
    /** 默认 true：fail + block → 返回 blocked=true 供 DS exit 1 */
    private Boolean blockOnFail;
    private List<String> ruleIds;
    /** 作业侧上报的逐条结果（可选） */
    private List<ResultItem> results;

    @Getter
    @Setter
    public static class ResultItem {
        private String ruleId;
        private Boolean pass;
        private Long okRows;
        private Long failRows;
        private BigDecimal okPct;
        private String message;
        private Boolean blocked;
    }
}
