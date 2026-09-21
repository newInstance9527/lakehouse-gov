package vip.xiaonuo.lh.modular.compliance.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 法务冻结 / 解除。冻结期间任何执行入口均被拒绝。
 */
@Getter
@Setter
public class GovDelHoldParam {

    @NotBlank
    @Schema(description = "请求 id 或 req_no")
    private String reqId;

    @Schema(description = "冻结原因：诉讼保全 / 监管调查 / 法定保存期")
    private String reason;

    @Schema(description = "冻结范围")
    private String scope;

    @Schema(description = "冻结至")
    private Date holdUntil;

    @Schema(description = "来源：法务 / 监管 / 系统")
    private String source;
}
