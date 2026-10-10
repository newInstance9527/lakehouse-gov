package vip.xiaonuo.lh.modular.bi.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
@Schema(description = "按维打开已发布指标数据集（重编译 SQL 后深链）")
public class GovBiOpenDatasetParam {

    @Schema(description = "门户 gov_bi_dataset.id")
    private String id;

    @Schema(description = "或 metric_code（取该编码最新已同步记录）")
    private String metricCode;

    @Schema(description = "工作空间")
    private String ws;

    @Schema(description = "与看板同契约：可含 dims；或整包 filters:{dims:{}}")
    private Map<String, Object> filters;

    @Schema(description = "直接 params（优先于 filters 展开）")
    private Map<String, Object> params;
}
