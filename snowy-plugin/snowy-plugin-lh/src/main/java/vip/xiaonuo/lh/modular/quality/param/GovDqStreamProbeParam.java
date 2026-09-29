package vip.xiaonuo.lh.modular.quality.param;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Flink 流式探针回调：写 VM {@code lh_dq_stream_*}（夜莺即时告警）；可选落 {@code gov_dq_rule_run}。
 * <p>流式通道<strong>不</strong>阻断 DAG（与批 evaluate 分工）。</p>
 */
@Getter
@Setter
public class GovDqStreamProbeParam {
    /** 优先按规则 id */
    private String ruleId;
    private String ruleCode;
    private String tableName;
    private String ws;
    /** Flink 作业 id / 算子名 */
    private String jobId;
    private Boolean pass;
    private Long okRows;
    private Long failRows;
    private BigDecimal okPct;
    /** 端到端延迟毫秒 */
    private Long lagMs;
    /** 失败比例 0–100（可与 okPct 二选一） */
    private BigDecimal failRatio;
    private Long sampleCount;
    private String message;
    /** 默认 true：落一条 run（job_run_id=stream:…）；false 仅写 VM */
    private Boolean persistRun;
}
