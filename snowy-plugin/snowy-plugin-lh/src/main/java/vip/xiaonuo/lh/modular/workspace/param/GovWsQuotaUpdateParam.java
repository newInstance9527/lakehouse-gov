package vip.xiaonuo.lh.modular.workspace.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 更新工作空间配额。各字段：{@code null} = 本请求不修改该维；
 * AI 维度额外：传 {@code 0} 表示不限（写入库 NULL）。
 */
@Getter
@Setter
@Schema(description = "工作空间配额更新")
public class GovWsQuotaUpdateParam {

    @Schema(description = "存储上限 TB；省略不改")
    private BigDecimal storageQuotaTb;

    @Schema(description = "CU 日上限；省略不改")
    private Integer cuQuota;

    @Schema(description = "Trino 并发上限；省略不改")
    private Integer trinoQuota;

    @Schema(description = "API QPS 上限；省略不改")
    private Integer apiQpsQuota;

    @Schema(description = "AI Token 日上限；省略不改；传 0 = 不限")
    private Long aiTokenQuota;

    @Schema(description = "AI 成本日上限；省略不改；传 0 = 不限")
    private BigDecimal aiCostQuota;
}
