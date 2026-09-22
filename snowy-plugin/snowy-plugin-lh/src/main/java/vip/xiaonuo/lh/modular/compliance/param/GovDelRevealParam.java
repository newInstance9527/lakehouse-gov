package vip.xiaonuo.lh.modular.compliance.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 查看主体 ID 明文：二次授权（回填请求号 + 用途说明），写审计后从 Vault 读取。
 */
@Getter
@Setter
public class GovDelRevealParam {

    @NotBlank
    @Schema(description = "请求 id 或 req_no")
    private String reqId;

    @NotBlank
    @Schema(description = "二次确认：必须回填 req_no")
    private String confirmReqNo;

    @NotBlank
    @Schema(description = "查看用途（写入审计；禁止空）")
    private String reason;
}
