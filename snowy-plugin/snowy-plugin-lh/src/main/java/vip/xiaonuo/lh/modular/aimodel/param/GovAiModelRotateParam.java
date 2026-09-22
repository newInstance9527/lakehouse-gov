package vip.xiaonuo.lh.modular.aimodel.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 轮换模型 API Key（明文仅本次请求；写入 Vault 后丢弃）
 */
@Getter
@Setter
public class GovAiModelRotateParam {

    @NotBlank
    @Schema(description = "新 API Key", requiredMode = Schema.RequiredMode.REQUIRED)
    private String key;

    @Schema(description = "可选：新过期时间")
    private Date keyExpiresAt;
}
