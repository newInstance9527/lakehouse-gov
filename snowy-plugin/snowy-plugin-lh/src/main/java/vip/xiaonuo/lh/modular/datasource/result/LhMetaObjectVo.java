package vip.xiaonuo.lh.modular.datasource.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 * JDBC 元数据表/视图对象
 */
@Getter
@Setter
@Schema(description = "元数据对象（表/视图）")
public class LhMetaObjectVo {

    @Schema(description = "对象名")
    private String name;

    @Schema(description = "TABLE 或 VIEW")
    private String objectKind;

    @Schema(description = "备注，可为空串")
    private String remarks;
}
