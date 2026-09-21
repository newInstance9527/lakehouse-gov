package vip.xiaonuo.lh.modular.query.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 * 查询历史
 */
@Getter
@Setter
public class CpQueryHistoryParam {

    @Schema(description = "工作空间")
    private String ws;

    @Schema(description = "条数，默认 30")
    private Integer limit;

    @Schema(description = "仅本人；默认 true")
    private Boolean mineOnly;
}
