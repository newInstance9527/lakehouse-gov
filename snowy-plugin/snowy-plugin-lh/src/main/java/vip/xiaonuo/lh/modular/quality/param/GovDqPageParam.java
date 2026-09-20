package vip.xiaonuo.lh.modular.quality.param;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GovDqPageParam {
    private String q;
    private String keyword;
    private String ws;
    private String layer;
    private String status;
    private String range;
    /** 精确表名（防串表） */
    private String tableName;
    /** 门户资产 id */
    private String assetId;
    private String sortField;
    private String sortOrder;
}
