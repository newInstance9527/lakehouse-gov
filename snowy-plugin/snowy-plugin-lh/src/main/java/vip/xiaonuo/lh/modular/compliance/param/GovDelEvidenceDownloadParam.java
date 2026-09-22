package vip.xiaonuo.lh.modular.compliance.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 二次授权下载证据包：回填请求号 + 用途，写审计后从对象存储取 ZIP。
 */
@Getter
@Setter
public class GovDelEvidenceDownloadParam {

    @NotBlank
    @Schema(description = "请求 id 或 req_no")
    private String reqId;

    @NotBlank
    @Schema(description = "二次确认：必须回填 req_no")
    private String confirmReqNo;

    @NotBlank
    @Schema(description = "下载用途（写入审计；禁止空）")
    private String reason;
}
