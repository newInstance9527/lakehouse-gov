package vip.xiaonuo.lh.modular.aimodel.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GovAiRouteUpsertParam {

    private String id;

    @Schema(description = "场景 sql/script/diagnose/manual/sandbox/embed")
    private String scene;

    @Schema(description = "空间范围 * 或具体 ws")
    private String wsScope;

    private String primaryModelId;
    private String fallbackModelId;
    private Boolean enabled;
    private String remark;
    private String ws;
}
