package vip.xiaonuo.lh.modular.metric.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
public class GovMetricBoardQueryParam {

    @Schema(description = "工作空间")
    private String ws;

    @Schema(description = "路由 prefer=hot|trino，默认 trino")
    private String prefer;

    @Schema(description = "报表层筛选；filters.dims ≡ params.dims")
    private Map<String, Object> filters;

    @Schema(description = "单卡最大行数，默认 20")
    private Integer maxRows;
}
