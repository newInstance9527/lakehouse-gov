package vip.xiaonuo.lh.modular.compliance.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@Schema(description = "合规删除计划项")
public class GovDelTargetVo {

    private String id;
    private String reqId;
    private String carrier;
    private String carrierLabel;
    private Integer carrierOrder;
    private String objectFqn;
    private String scopeExpr;
    private String mode;
    private String modeLabel;
    private Long rowsEst;
    private Long rowsVerified;
    private String status;
    private String statusLabel;
    private String engineRef;
    private String excludeReason;
    private Date reviewAt;
    private Boolean manualAdded;
    private String lineageConfidence;
    private Integer lineageHop;
    private String lineageLayer;
    private String owner;
    private String sensitivity;
    private Boolean hasSubjectCol;
    private Boolean lineageConfirmed;
    /** inferred 且未确认 → 提交前须确认 */
    private Boolean needsConfirm;
}
