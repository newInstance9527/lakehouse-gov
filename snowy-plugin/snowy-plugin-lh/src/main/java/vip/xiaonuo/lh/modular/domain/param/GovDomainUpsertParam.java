package vip.xiaonuo.lh.modular.domain.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 数据域创建/更新
 */
@Getter
@Setter
public class GovDomainUpsertParam {

    @Schema(description = "域编码（创建必填，小写）")
    private String domainCode;

    @NotBlank(message = "name 不能为空")
    @Schema(description = "展示名")
    private String name;

    @Schema(description = "责任人")
    private String owner;

    @Schema(description = "排序")
    private Integer sortNo;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "工作空间")
    private String ws;

    @Schema(description = "状态 active|disabled（可选）")
    private String status;
}
