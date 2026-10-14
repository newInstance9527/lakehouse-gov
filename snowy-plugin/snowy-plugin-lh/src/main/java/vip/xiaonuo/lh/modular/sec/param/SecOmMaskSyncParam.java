package vip.xiaonuo.lh.modular.sec.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SecOmMaskSyncParam {

    @Schema(description = "单资产同步；空则按 ws 批处理")
    private String assetId;

    @Schema(description = "工作空间；批处理时必填或回落 default")
    private String ws;

    @Schema(description = "true=只预览不落库")
    private Boolean dryRun;
}
