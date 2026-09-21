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
    private String storageLabel;
    private String cuLabel;
    private String trinoLabel;
    private String apiLabel;
}
