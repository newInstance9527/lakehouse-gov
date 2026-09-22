package vip.xiaonuo.lh.modular.compliance.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GovDelExportGateParam {

    @NotBlank(message = "exportTable不能为空")
    @Schema(description = "出湖源表名/FQN")
    private String exportTable;
}
