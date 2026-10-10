package vip.xiaonuo.lh.modular.bi.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "置顶/取消置顶看板")
public class GovBiDashboardPinParam {

    @NotBlank
    @Schema(description = "门户 gov_bi_dashboard.id")
    private String id;

    @Schema(description = "是否置顶")
    private Boolean pinned;

    @Schema(description = "关联指标编码（逗号分隔，可选）")
    private String metricCodes;

    @Schema(description = "工作空间")
    private String ws;
}
