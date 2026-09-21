package vip.xiaonuo.lh.modular.aimodel.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GovAiModelPageParam {

    @Schema(description = "关键词")
    private String q;

    @Schema(description = "工作空间")
    private String ws;

    @Schema(description = "chat|embed")
    private String kind;

    @Schema(description = "排序字段")
    private String sortField;

    @Schema(description = "排序方式")
    private String sortOrder;
}
