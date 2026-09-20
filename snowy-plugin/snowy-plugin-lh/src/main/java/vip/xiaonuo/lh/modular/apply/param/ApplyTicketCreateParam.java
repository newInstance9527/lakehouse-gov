package vip.xiaonuo.lh.modular.apply.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ApplyTicketCreateParam {

    @Schema(description = "类型：table_read（默认）| lake_export（出湖）；兼容前端 export/perm")
    private String ticketType;

    @NotBlank
    @Schema(description = "标题")
    private String title;

    @Schema(description = "事由")
    private String reason;

    @Schema(description = "gov_asset.id（table_read MUST；lake_export 可选）")
    private String assetId;

    @Schema(description = "权限，默认 SELECT（table_read）")
    private String privilege;

    @Schema(description = "时效文案，如 30天/长期")
    private String expireLabel;

    @Schema(description = "申请列（可选）")
    private String columns;

    @Schema(description = "出湖源表名/编码（lake_export MUST）")
    private String exportTable;

    @Schema(description = "出湖目标（lake_export MUST）")
    private String exportTarget;
}
