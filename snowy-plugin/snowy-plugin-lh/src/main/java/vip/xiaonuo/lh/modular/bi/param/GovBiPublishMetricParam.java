package vip.xiaonuo.lh.modular.bi.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
@Schema(description = "发布指标为 Superset 数据集")
public class GovBiPublishMetricParam {

    @NotBlank
    @Schema(description = "指标编码")
    private String metricCode;

    @Schema(description = "钉死版本；空则用当前启用版")
    private String ver;

    @Schema(description = "工作空间")
    private String ws;

    @Schema(description = "门户 Trino 数据源 id；空则取已 synced 的 query_gateway")
    private String dsId;

    @Schema(description = "编译参数（可含 dims，与 filters.dims 同契约）")
    private Map<String, Object> params;
}
