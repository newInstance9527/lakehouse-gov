package vip.xiaonuo.lh.modular.lineage.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GovLineageEdgeUpsertParam {
    private String id;
    private String ws;
    @NotBlank(message = "fromTable 不能为空")
    private String fromTable;
    @NotBlank(message = "fromField 不能为空")
    private String fromField;
    @NotBlank(message = "toTable 不能为空")
    private String toTable;
    @NotBlank(message = "toField 不能为空")
    private String toField;
    private String transformText;
    private String confidence;
    private String etlJobId;
    private String omFromFqn;
    private String omToFqn;
    private String remark;
}
