package vip.xiaonuo.lh.modular.quality.param;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 质量门禁写入。{@code tableName} / {@code layer} / {@code assetId} 均可空，
 * 但至少填其一（表名空=整层阈值）。
 */
@Getter
@Setter
public class GovDqGateUpsertParam {
    private String id;
    private String ws;
    private String assetId;
    /** 可选；空表示整层门禁 */
    private String tableName;
    private String layer;
    private BigDecimal minScore;
    private Boolean blockOnFail;
}
