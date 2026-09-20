package vip.xiaonuo.lh.modular.apply.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ApplyTicketDecideParam {

    @NotBlank
    @Schema(description = "申请单ID")
    private String id;

    @Schema(description = "审批备注")
    private String remark;
}
