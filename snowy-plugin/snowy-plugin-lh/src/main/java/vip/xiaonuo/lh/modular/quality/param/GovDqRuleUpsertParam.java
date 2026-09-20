package vip.xiaonuo.lh.modular.quality.param;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GovDqRuleUpsertParam {
    private String id;
    private String ws;
    @NotBlank(message = "ruleCode 不能为空")
    private String ruleCode;
    @NotBlank(message = "ruleType 不能为空")
    private String ruleType;
    private String ruleLevel;
    private String scope;
    @NotBlank(message = "tableName 不能为空")
    private String tableName;
    private String assetId;
    private String fieldName;
    private String layer;
    private String exprText;
    private String severity;
    private Boolean enabled;
    private String omTestFqn;
    private String remark;
}
