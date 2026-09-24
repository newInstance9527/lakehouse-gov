package vip.xiaonuo.lh.modular.workspace.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Schema(description = "工作空间配额 VO")
public class GovWsQuotaVo {

    private String wsCode;
    private String status;
    private BigDecimal storageQuotaTb;
    private BigDecimal storageUsedTb;
    private Integer storagePct;
    private Integer cuQuota;
    private Integer cuUsed;
    private Integer cuPct;
    private Integer trinoQuota;
    private Integer trinoUsed;
    private Integer apiQpsQuota;
    private Integer apiQpsUsed;
    /** AI Token 日上限；null/0 = 不限 */
    private Long aiTokenQuota;
    /** 今日已用 Token（gov_ai_usage_daily 汇总） */
    private Long aiTokenUsed;
    private Integer aiTokenPct;
    /** AI 成本日上限；null/0 = 不限 */
    private BigDecimal aiCostQuota;
    /** 今日已用成本 */
    private BigDecimal aiCostUsed;
    private Integer aiCostPct;
    private String storageLabel;
    private String cuLabel;
    private String trinoLabel;
    private String apiLabel;
    private String aiTokenLabel;
    private String aiCostLabel;
    /** 今日剩余 Token；不限时为 null */
    private Long aiTokenRemaining;
    /** 今日剩余成本；不限时为 null */
    private BigDecimal aiCostRemaining;
    /** true = Token/成本至少一项有日上限 */
    private Boolean aiLimited;
}
