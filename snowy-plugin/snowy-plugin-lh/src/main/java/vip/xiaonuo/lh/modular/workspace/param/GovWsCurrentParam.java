package vip.xiaonuo.lh.modular.workspace.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GovWsCurrentParam {

    @NotBlank
    @Schema(description = "当前协作空间编码")
    private String wsCode;
}
