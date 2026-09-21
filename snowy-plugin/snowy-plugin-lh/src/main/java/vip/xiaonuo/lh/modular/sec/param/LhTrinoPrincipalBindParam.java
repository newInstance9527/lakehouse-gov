package vip.xiaonuo.lh.modular.sec.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LhTrinoPrincipalBindParam {

    @NotBlank
    @Schema(description = "门户用户 id")
    private String portalUserId;

    @NotBlank
    @Schema(description = "门户登录名，仅展示")
    private String portalAccount;

    @NotBlank
    @Schema(description = "Gravitino/Trino 中已存在的主体，禁止填服务账号")
    private String trinoUser;

    @Schema(description = "备注")
    private String remark;
}
