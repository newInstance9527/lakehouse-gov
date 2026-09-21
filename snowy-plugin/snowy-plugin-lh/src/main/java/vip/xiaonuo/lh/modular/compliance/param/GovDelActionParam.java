package vip.xiaonuo.lh.modular.compliance.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 通用动作：评估 / dry-run / 提交审批 / 排期执行 / 验证 / 中止。
 */
@Getter
@Setter
public class GovDelActionParam {

    @NotBlank
    @Schema(description = "请求 id 或 req_no")
    private String reqId;

    @Schema(description = "执行窗口；缺省次日 02:00")
    private Date execWindow;

    @Schema(description = "紧急通道（监管责令）：跳过窗口立即执行，需安全负责人确认")
    private Boolean urgent;

    @Schema(description = "幂等键；同键重复提交返回首次结果")
    private String execKey;

    @Schema(description = "二次确认：必须回填 req_no")
    private String confirmReqNo;

    @Schema(description = "备注 / 原因")
    private String remark;
}
