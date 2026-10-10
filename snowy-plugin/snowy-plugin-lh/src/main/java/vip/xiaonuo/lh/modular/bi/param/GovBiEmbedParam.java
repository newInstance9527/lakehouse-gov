package vip.xiaonuo.lh.modular.bi.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
@Schema(description = "签发 Guest Token 内嵌会话")
public class GovBiEmbedParam {

    @Schema(description = "门户 gov_bi_dashboard.id")
    private String id;

    @Schema(description = "或直接传 Superset dashboard 数字 id")
    private Long supersetDashboardId;

    @Schema(description = "工作空间")
    private String ws;

    @Schema(description = "额外允许的 Origin（可选，并入配置域）")
    private String origin;

    @Schema(description = "维筛选（filters.dims）；回传前端作 urlParams，SQL 切片仍以 openDatasetWithDims 为准")
    private Map<String, Object> filters;

    @Schema(description = "同 filters，兼容 params.dims")
    private Map<String, Object> params;
}
