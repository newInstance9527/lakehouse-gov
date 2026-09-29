package vip.xiaonuo.lh.modular.sec.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 安全中心 Vault 轮换（对齐数据源 rotateCred：打 rotatedAt 戳并刷新绑定；不生成新明文密码）。
 */
@Getter
@Setter
public class SecVaultRotateParam {

    @NotBlank(message = "vaultPath不能为空")
    @Schema(description = "ig_secret_store.vault_path", requiredMode = Schema.RequiredMode.REQUIRED)
    private String vaultPath;
}
