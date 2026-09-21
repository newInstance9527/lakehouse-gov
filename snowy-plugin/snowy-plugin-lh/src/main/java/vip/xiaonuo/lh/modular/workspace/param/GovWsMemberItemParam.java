package vip.xiaonuo.lh.modular.workspace.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GovWsMemberItemParam {

    @Schema(description = "user/sa/group")
    private String subjectType;

    @NotBlank
    @Schema(description = "主体 ID/账号")
    private String subjectId;

    @Schema(description = "展示名")
    private String displayName;

    @NotBlank
    @Schema(description = "Owner/Developer/Operator/BusinessUser/SecurityOfficer/ServiceAccount")
    private String roleCode;

    @Schema(description = "门户职责说明")
    private String scopeNote;
}
