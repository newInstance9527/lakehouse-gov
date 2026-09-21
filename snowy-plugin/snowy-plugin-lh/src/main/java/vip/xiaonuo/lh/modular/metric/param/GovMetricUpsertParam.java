package vip.xiaonuo.lh.modular.metric.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 新建草稿 / 更新草稿
 */
@Getter
@Setter
public class GovMetricUpsertParam {

    @Schema(description = "更新时必填：指标编码")
    private String metricCode;

    @NotBlank
    @Schema(description = "类型 原子/衍生/复合")
    private String kind;

    @NotBlank
    @Schema(description = "指标名")
    private String name;

    @Schema(description = "业务域编码或中文")
    private String domain;

    @Schema(description = "单位")
    private String unit;

    @Schema(description = "业务口径说明")
    private String caliber;

    @Schema(description = "负责人")
    private String owner;

    @Schema(description = "工作空间")
    private String ws;

    @Schema(description = "备注")
    private String remark;

    // —— 原子 ——
    @Schema(description = "绑定表")
    private String table;

    @Schema(description = "绑定字段")
    private String field;

    @Schema(description = "聚合 COUNT/SUM/…")
    private String agg;

    @Schema(description = "Gravitino 资产指针（gov_asset.grav_asset_id）")
    private String gravAssetId;

    @Schema(description = "门户资产 id（gov_asset.id；优先于 table 反查）")
    private String assetId;

    // —— 衍生 ——
    @Schema(description = "依赖原子指标编码")
    private String atomRef;

    @Schema(description = "业务限定列表")
    private List<String> qualifier;

    @Schema(description = "统计粒度列表")
    private List<String> dim;

    @Schema(description = "统计周期")
    private String time;

    // —— 复合 ——
    @Schema(description = "依赖指标编码列表")
    private List<String> deriveRef;

    @Schema(description = "计算公式")
    private String formula;
}
