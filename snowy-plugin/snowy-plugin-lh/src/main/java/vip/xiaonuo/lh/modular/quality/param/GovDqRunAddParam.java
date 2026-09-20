package vip.xiaonuo.lh.modular.quality.param;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class GovDqRunAddParam {
    @NotBlank(message = "ruleId 不能为空")
    private String ruleId;
    private String ws;
    private Boolean pass;
    private Long okRows;
    private Long failRows;
    private BigDecimal okPct;
    private Boolean blocked;
    private String jobRunId;
    private String message;
}
