package vip.xiaonuo.lh.modular.aimodel.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GovAiModelEnableParam {

    @Schema(description = "是否启用")
    private Boolean enabled;
}
