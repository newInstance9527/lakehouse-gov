package vip.xiaonuo.lh.modular.query.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CpQuerySavedSaveParam {

    @Schema(description = "已有 id（同名更新时可选）")
    private String id;

    @Schema(description = "脚本名")
    private String name;

    @Schema(description = "工作空间")
    private String ws;

    @Schema(description = "SQL 正文")
    private String sql;

    @Schema(description = "引擎，默认 trino")
    private String engine;
}
