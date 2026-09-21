package vip.xiaonuo.lh.modular.query.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 * 即席导出登记（客户端生成脱敏 CSV；本接口写审计）
 */
@Getter
@Setter
public class CpQueryExportParam {

    @Schema(description = "门户 queryId")
    private String queryId;

    @Schema(description = "导出行数")
    private Integer rowCount;

    @Schema(description = "工作空间")
    private String ws;
}
