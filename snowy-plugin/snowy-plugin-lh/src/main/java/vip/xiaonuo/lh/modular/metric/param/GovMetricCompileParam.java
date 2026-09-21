package vip.xiaonuo.lh.modular.metric.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GovMetricCompileParam {

    @Schema(description = "已有指标编码（优先）")
    private String metricCode;

    @Schema(description = "方言，默认 trino")
    private String dialect;

    @Schema(description = "工作空间")
    private String ws;

    @Schema(description = "是否写入编译缓存（仅已落库版本）")
    private Boolean persist;

    @Schema(description = "未落库时的临时定义（试编译）")
    private GovMetricUpsertParam draft;
}
