package vip.xiaonuo.lh.modular.compliance.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 登记每主体 PII 列信封加密 DEK（crypto-shredding 前置）。
 * <p>{@code subjectId} 即时 HMAC，不落库。</p>
 */
@Getter
@Setter
public class GovDelDekRegisterParam {

    @NotBlank
    @Schema(description = "主体 ID 明文（即时 HMAC）")
    private String subjectId;

    @Schema(description = "主体类型，默认 user")
    private String subjectType;

    @NotBlank
    @Schema(description = "表 FQN；可用 table#column 内嵌列名")
    private String objectFqn;

    @Schema(description = "PII 列名；缺省从 objectFqn#col 解析或 pii")
    private String columnName;

    @Schema(description = "工作空间")
    private String ws;

    @Schema(description = "备注")
    private String remark;
}
