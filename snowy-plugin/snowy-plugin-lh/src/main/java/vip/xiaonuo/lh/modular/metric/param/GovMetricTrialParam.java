package vip.xiaonuo.lh.modular.metric.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
public class GovMetricTrialParam {

    @Schema(description = "工作空间")
    private String ws;

    @Schema(description = "绑定参数")
    private Map<String, Object> params;

    @Schema(description = "最大行数，默认 200")
    private Integer maxRows;
}
