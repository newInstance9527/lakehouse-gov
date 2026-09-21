package vip.xiaonuo.lh.modular.lifecycle.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 * 运行留痕分页
 */
@Getter
@Setter
public class GovLcRunPageParam {

    @Schema(description = "工作空间")
    private String ws;

    @Schema(description = "compact/expire/orphan/daily/partition")
    private String kind;

    @Schema(description = "表 FQN")
    private String tableFqn;

    @Schema(description = "queued/running/success/failed")
    private String status;

    @Schema(description = "当前页")
    private Integer current;

    @Schema(description = "页大小")
    private Integer size;
}
