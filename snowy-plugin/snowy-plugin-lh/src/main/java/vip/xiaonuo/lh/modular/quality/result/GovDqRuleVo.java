package vip.xiaonuo.lh.modular.quality.result;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.Date;

@Getter
@Setter
public class GovDqRuleVo {
    private String id;
    private String ruleCode;
    private String ruleType;
    private String ruleLevel;
    private String scope;
    private String tableName;
    private String assetId;
    private String fieldName;
    private String layer;
    private String exprText;
    private String severity;
    private Boolean enabled;
    private String omTestFqn;
    /** 绑定 gov_std_code.code_set_id */
    private String stdCodeSetId;
    /** 最近运行 */
    private Boolean pass;
    private BigDecimal okPct;
    private Long okRows;
    private Long failRows;
    private Boolean blocked;
    private String statusText;
    private Date ranAt;
    private String message;
    /** 最近运行 job_run_id（stream:… 为流式） */
    private String jobRunId;
}
