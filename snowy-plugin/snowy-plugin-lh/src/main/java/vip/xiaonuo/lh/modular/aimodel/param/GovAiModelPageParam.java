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

    @Schema(description = "chat|embed|image；all 或不传则不过滤")
    private String kind;

    @Schema(description = "仅返回支持视觉输入的模型（true/1）")
    private Boolean supportsVision;

    @Schema(description = "排序字段")
    private String sortField;

    @Schema(description = "排序方式")
    private String sortOrder;
}
