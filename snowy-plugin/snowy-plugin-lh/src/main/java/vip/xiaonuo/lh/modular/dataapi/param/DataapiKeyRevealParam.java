package vip.xiaonuo.lh.modular.dataapi.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 订阅 Key 二次查看密文：从 Vault 读取 Bearer token；写操作审计（禁止落库明文）。
 */
@Getter
@Setter
public class DataapiKeyRevealParam {

    @NotBlank(message = "id 不能为空")
    @Schema(description = "dataapi_api_key_meta.id")
    private String id;

    @Schema(description = "查看用途（写入审计；可空，默认「查看订阅密钥」）")
    private String reason;
}
