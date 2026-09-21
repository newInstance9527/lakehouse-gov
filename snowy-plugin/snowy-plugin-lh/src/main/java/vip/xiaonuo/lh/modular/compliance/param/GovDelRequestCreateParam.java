package vip.xiaonuo.lh.modular.compliance.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 创建合规删除请求。{@code subjectId} 仅用于即时 HMAC 与掩码，不落库。
 */
@Getter
@Setter
public class GovDelRequestCreateParam {

    @NotBlank
    @Schema(description = "主体 ID 明文（仅用于 HMAC 与掩码，不落库）")
    private String subjectId;

    @Schema(description = "主体类型：user/order/device/account/contract")
    private String subjectType;

    @Schema(description = "请求类型：forget/erase_error/regulator/contract_expire/account_close")
    private String reqType;

    @Schema(description = "法律/业务依据")
    private String legalBasis;

    @Schema(description = "删除范围文案：指定行/分区/日志归档")
    private String scopeLabel;

    @Schema(description = "来源系统：法务/客服/人工")
    private String sourceSystem;

    @Schema(description = "来源单号")
    private String sourceRef;

    @Schema(description = "截止日期；缺省按 15 个工作日")
    private Date deadline;

    @Schema(description = "工作空间")
    private String ws;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建后立即评估（默认 true）")
    private Boolean autoAssess;
}
