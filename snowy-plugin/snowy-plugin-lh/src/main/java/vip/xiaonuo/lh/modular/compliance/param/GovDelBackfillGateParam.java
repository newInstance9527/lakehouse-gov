package vip.xiaonuo.lh.modular.compliance.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class GovDelBackfillGateParam {

    @Schema(description = "DAG 涉及表 FQN 列表（含 catalog.schema.table）")
    private List<String> tables;

    @NotBlank(message = "markKey不能为空")
    @Schema(description = "水位键，如 dt")
    private String markKey;

    @NotBlank(message = "markValue不能为空")
    @Schema(description = "水位值，如 2026-08")
    private String markValue;
}
