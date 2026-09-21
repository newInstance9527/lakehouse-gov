package vip.xiaonuo.lh.modular.lifecycle.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 * 孤儿扫描
 */
@Getter
@Setter
public class GovLcOrphanScanParam {

    @Schema(description = "工作空间")
    private String ws;

    @Schema(description = "桶名；空则扫描空间内已知桶")
    private String bucket;

    @Schema(description = "默认 true；P0 物理删拒绝")
    private Boolean dryRun;
}
