package vip.xiaonuo.lh.modular.lifecycle.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 * 表级画像查询参数（doc/存储趋势.md §5.1）
 */
@Getter
@Setter
public class GovLcStorageTablesParam {

    @Schema(description = "工作空间")
    private String ws;

    @Schema(description = "窗口：7d / 30d / 90d，默认 30d")
    private String range;

    @Schema(description = "分层 ODS/DWD/DWS/ADS/DIM")
    private String layer;

    @Schema(description = "筛选：anomaly / reclaimable / smallfile / all")
    private String filter;

    @Schema(description = "排序字段：activeBytes / totalBytes / reclaimableBytes / growthPct / netGrowthBytes / smallFileRatio")
    private String sort;

    @Schema(description = "asc / desc，默认 desc")
    private String order;

    @Schema(description = "页码，从 1 起")
    private Integer page;

    @Schema(description = "页大小，默认 20")
    private Integer size;
}
