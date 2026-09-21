package vip.xiaonuo.lh.modular.aimodel.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "AI路由VO")
public class GovAiRouteVo {

    private String id;
    private String scene;
    private String wsScope;
    private String primaryModelId;
    private String primaryModelName;
    private String fallbackModelId;
    private String fallbackModelName;
    private Boolean enabled;
    private String status;
    private String remark;
}
