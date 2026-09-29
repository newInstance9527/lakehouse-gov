package vip.xiaonuo.lh.modular.sec.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SecJobSaRegisterParam {

    @Schema(description = "工作空间")
    private String ws;

    @NotBlank(message = "saName 不能为空")
    @Schema(description = "job.{domain}.{action}", requiredMode = Schema.RequiredMode.REQUIRED)
    private String saName;

    @Schema(description = "业务域")
    private String domain;

    @Schema(description = "绑定作业清单")
    private String jobBind;

    @Schema(description = "权限范围")
    private String privilegeScope;

    @Schema(description = "有效期 ISO 或 yyyy-MM-dd HH:mm:ss；空=不过期")
    private String expireAt;
}
