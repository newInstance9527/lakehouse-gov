package vip.xiaonuo.lh.modular.sec.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SecJobSaIdParam {

    @NotBlank(message = "id 不能为空")
    @Schema(description = "sec_job_sa.id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String id;
}
