package vip.xiaonuo.lh.modular.metric.param;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GovMetricQualifierUpsertParam {
    private String ws;
    @NotBlank(message = "code 不能为空")
    private String code;
    @NotBlank(message = "name 不能为空")
    private String name;
    private String domainCode;
    private String tablePattern;
    @NotBlank(message = "exprDsl 不能为空")
    private String exprDsl;
    private String remark;
    private String status;
}
