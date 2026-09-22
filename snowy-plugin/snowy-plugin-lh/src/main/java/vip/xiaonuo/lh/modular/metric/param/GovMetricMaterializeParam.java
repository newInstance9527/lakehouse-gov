package vip.xiaonuo.lh.modular.metric.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 * 指标物化登记 / 触发作业
 */
@Getter
@Setter
@Schema(description = "指标物化参数")
public class GovMetricMaterializeParam {

    @Schema(description = "引擎：clickhouse / iceberg；默认 clickhouse")
    private String engine;

    @Schema(description = "物化目标表；空则 ads.metric_{code}_d")
    private String targetTable;

    @Schema(description = "分区日期 yyyy-MM-dd；默认昨天")
    private String partitionDt;

    @Schema(description = "是否启动 DS 作业；默认 true")
    private Boolean launch;

    @Schema(description = "工作空间")
    private String ws;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "粒度 JSON 数组，默认 [\"dt\"]")
    private String grainJson;
}
