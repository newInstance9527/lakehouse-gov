package vip.xiaonuo.lh.modular.query.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
public class CpQueryDatasetSaveParam {

    @Schema(description = "名称")
    private String name;

    @Schema(description = "工作空间")
    private String ws;

    @Schema(description = "来源 queryId")
    private String queryId;

    @Schema(description = "SQL")
    private String sql;

    @Schema(description = "列元数据")
    private List<Map<String, Object>> columns;

    @Schema(description = "抽样行（≤200）")
    private List<Map<String, Object>> rows;

    @Schema(description = "扫描字节")
    private Long scanBytes;
}
