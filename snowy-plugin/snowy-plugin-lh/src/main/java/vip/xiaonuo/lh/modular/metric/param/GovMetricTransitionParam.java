package vip.xiaonuo.lh.modular.metric.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GovMetricTransitionParam {

    @NotBlank
    @Schema(description = "指标编码")
    private String metricCode;

    @NotBlank
    @Schema(description = "submit/approve/reject/change/approveVersion/cancelChange/deprecate")
    private String action;

    @Schema(description = "变更说明 / 待审新口径")
    private String note;

    @Schema(description = "工作空间")
    private String ws;
}
