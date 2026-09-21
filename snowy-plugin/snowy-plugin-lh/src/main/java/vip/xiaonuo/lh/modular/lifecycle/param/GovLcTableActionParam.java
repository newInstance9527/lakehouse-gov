package vip.xiaonuo.lh.modular.lifecycle.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 单表 compact / expire
 */
@Getter
@Setter
public class GovLcTableActionParam {

    @NotBlank
    @Schema(description = "表 FQN")
    private String tableFqn;

    @Schema(description = "工作空间")
    private String ws;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "合规删除请求号。与 retainLast 同时出现时才允许覆盖过期参数")
    private String reqNo;

    @Schema(description = "定向过期保留快照数。仅合规工单 executing/verifying 且值为 1 时接受")
    private Integer retainLast;
}
