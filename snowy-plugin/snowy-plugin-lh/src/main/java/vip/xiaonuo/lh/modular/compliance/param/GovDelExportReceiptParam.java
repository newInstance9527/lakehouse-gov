package vip.xiaonuo.lh.modular.compliance.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 出湖副本删除回执 / 书面残留声明。
 * <p>
 * outcome：{@code received} 对方已删；{@code residual_statement} 书面残留；
 * {@code timeout_statement} 超期无回执声明。
 */
@Getter
@Setter
public class GovDelExportReceiptParam {

    @NotBlank
    @Schema(description = "请求 id 或 req_no")
    private String reqId;

    @Schema(description = "gov_del_target.id；与 objectFqn 二选一")
    private String targetId;

    @Schema(description = "出湖目标 object_fqn（export:…）")
    private String objectFqn;

    @NotBlank
    @Schema(description = "received / residual_statement / timeout_statement")
    private String outcome;

    @Schema(description = "对方回执编号或工单号")
    private String receiptRef;

    @Schema(description = "合作方 / 副本持有方")
    private String partner;

    @Schema(description = "说明（残留声明时必填）")
    private String note;
}
