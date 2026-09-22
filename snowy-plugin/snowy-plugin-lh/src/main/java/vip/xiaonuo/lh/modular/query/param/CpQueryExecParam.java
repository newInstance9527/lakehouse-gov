package vip.xiaonuo.lh.modular.query.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * 即席执行入参
 */
@Getter
@Setter
public class CpQueryExecParam {

    @NotBlank(message = "SQL 不能为空")
    @Schema(description = "SQL")
    private String sql;

    @Schema(description = "工作空间")
    private String ws;

    @Schema(description = "默认 catalog")
    private String catalog;

    @Schema(description = "默认 schema")
    private String schema;

    @Schema(description = "结果行上限，默认 1000")
    private Integer maxRows;

    @Schema(description = "强制跳过扫描治理（仅调试；生产应拒绝）")
    private Boolean force;

    @Schema(description = "提升配额：true 时申请硬顶 50GB；须持有已审批 scan_elevate→SCAN_ELEVATE grant（超管免审）")
    private Boolean elevated;

    @Schema(description = "命名参数 :name / ${name}")
    private Map<String, Object> params;
}
