package vip.xiaonuo.lh.modular.metric.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
public class GovMetricQueryParam {

    @NotBlank
    @Schema(description = "指标编码")
    private String metricCode;

    @Schema(description = "钉死版本，空=当前启用版")
    private String ver;

    @Schema(description = "方言 / 路由 prefer=hot|trino")
    private String prefer;

    @Schema(description = "绑定参数（时间窗等白名单）")
    private Map<String, Object> params;

    @Schema(description = "最大行数，默认 1000")
    private Integer maxRows;

    @Schema(description = "工作空间")
    private String ws;
}
