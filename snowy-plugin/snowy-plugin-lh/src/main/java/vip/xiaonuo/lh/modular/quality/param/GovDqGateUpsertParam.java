package vip.xiaonuo.lh.modular.quality.param;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class GovDqGateUpsertParam {
    private String id;
    private String ws;
    private String assetId;
    private String tableName;
    private String layer;
    private BigDecimal minScore;
    private Boolean blockOnFail;
}
