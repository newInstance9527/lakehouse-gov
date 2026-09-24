package vip.xiaonuo.lh.modular.org.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "部门非系统人员写入")
public class GovOrgPersonSaveParam {

    @Schema(description = "部门 id（SYS_ORG.id）")
    @NotBlank(message = "orgId不能为空")
    private String orgId;

    @Schema(description = "姓名")
    @NotBlank(message = "name不能为空")
    private String name;

    @Schema(description = "手机")
    private String phone;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "职务称谓")
    private String jobTitle;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "状态 active/disabled")
    private String status;
}
