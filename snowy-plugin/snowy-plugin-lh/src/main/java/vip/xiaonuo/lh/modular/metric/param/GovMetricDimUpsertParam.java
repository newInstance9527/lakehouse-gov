package vip.xiaonuo.lh.modular.metric.param;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GovMetricDimUpsertParam {
    private String ws;
    @NotBlank(message = "code 不能为空")
    private String code;
    @NotBlank(message = "name 不能为空")
    private String name;
    private String domainCode;
    @NotBlank(message = "columnName 不能为空")
    private String columnName;
    private String grainRole;
    private String factTable;
    private String bizProcess;
    /** DIM 层表 FQN；空=仅 grain 列，无成员下拉 */
    private String dimTable;
    private String memberCodeCol;
    private String memberNameCol;
    private String parentCodeCol;
    private String remark;
    private String status;
}
