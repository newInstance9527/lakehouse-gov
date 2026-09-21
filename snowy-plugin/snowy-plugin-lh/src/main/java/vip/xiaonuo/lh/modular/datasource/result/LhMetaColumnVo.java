package vip.xiaonuo.lh.modular.datasource.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 * JDBC 元数据列（对齐 SQLREST columns/get：name / type / remarks）
 */
@Getter
@Setter
@Schema(description = "元数据列")
public class LhMetaColumnVo {

    @Schema(description = "列名")
    private String name;

    @Schema(description = "类型（含精度）")
    private String type;

    @Schema(description = "注释/备注，可为空串")
    private String remarks;
}
