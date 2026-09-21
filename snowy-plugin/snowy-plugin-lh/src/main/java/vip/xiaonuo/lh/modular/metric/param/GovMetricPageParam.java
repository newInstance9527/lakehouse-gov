package vip.xiaonuo.lh.modular.metric.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GovMetricPageParam {

    @Schema(description = "关键词（编码/名称/口径）")
    private String q;

    @Schema(description = "业务域 trade/user/goods")
    private String domain;

    @Schema(description = "类型 原子/衍生/复合")
    private String kind;

    @Schema(description = "状态 draft/review/active/version_review/deprecated")
    private String status;

    @Schema(description = "工作空间")
    private String ws;

    @Schema(description = "排序字段")
    private String sortField;

    @Schema(description = "排序方式")
    private String sortOrder;
}
