package vip.xiaonuo.lh.modular.datasource.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 分层元数据浏览参数（门户数据源 id + schema/table）
 */
@Getter
@Setter
public class LhDatasourceMetaParam {

    @NotBlank(message = "id不能为空")
    @Schema(description = "门户数据源主键", requiredMode = Schema.RequiredMode.REQUIRED)
    private String id;

    @Schema(description = "Schema / 库名（MySQL 为 catalog）")
    private String schema;

    @Schema(description = "表或视图名（columns 接口必填）")
    private String table;
}
