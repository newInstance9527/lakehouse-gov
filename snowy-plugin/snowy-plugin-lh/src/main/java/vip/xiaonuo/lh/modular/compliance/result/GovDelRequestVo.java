package vip.xiaonuo.lh.modular.compliance.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 合规删除请求 VO。**不含**主体 ID 明文，仅掩码。
 */
@Getter
@Setter
@Schema(description = "合规删除请求")
public class GovDelRequestVo {

    private String id;
    private String reqNo;
    private String ws;
    private String status;
    private String statusLabel;
    private String subjectType;
    private String subjectMasked;
    private String reqType;
    private String reqTypeLabel;
    private String legalBasis;
    private String scopeLabel;
    private String sourceSystem;
    private String sourceRef;
    private String ticketNo;
    private String applicant;
    private Date deadline;
    private Date execWindow;
    private Date executedAt;
    private Date verifiedAt;
    private Date destroyAfter;
    private String holdReason;
    private String remark;
    private Date createTime;

    @Schema(description = "距截止剩余天数；负数为超期")
    private Long daysLeft;

    @Schema(description = "SLA 灯：ok/warn/overdue")
    private String slaLevel;

    @Schema(description = "计划项统计：total/included/done/restricted/failed")
    private Map<String, Object> planSummary;

    @Schema(description = "计划项明细（详情接口返回）")
    private List<GovDelTargetVo> targets;

    @Schema(description = "执行流水（详情接口返回）")
    private List<Map<String, Object>> timeline;

    @Schema(description = "覆盖率缺口表（评估后返回）")
    private List<String> gapTables;
}
