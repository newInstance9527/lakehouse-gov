package vip.xiaonuo.lh.modular.compliance.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 外部 DSR / 法务 webhook 送单（OneTrust / Fides 形态薄受理）。
 * <p>须带 {@code X-Lh-Intake-Signature}；主体明文即时 HMAC，不落门户表。</p>
 */
@Getter
@Setter
public class GovDelIntakeParam {

    @NotBlank
    @Schema(description = "主体 ID 明文")
    private String subjectId;

    @Schema(description = "主体类型，默认 user")
    private String subjectType;

    @Schema(description = "请求类型：forget/erase_error/regulator/…")
    private String reqType;

    @Schema(description = "法律/业务依据")
    private String legalBasis;

    @Schema(description = "来源系统：onetrust/fides/legal/…")
    private String sourceSystem;

    @Schema(description = "来源单号（幂等键，与 sourceSystem 组合）")
    private String sourceRef;

    @Schema(description = "外部原始 ID（写入 remark）")
    private String externalId;

    @Schema(description = "工作空间")
    private String ws;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "受理后是否立即评估，默认 true")
    private Boolean autoAssess;
}
