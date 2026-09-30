package vip.xiaonuo.lh.modular.dataapi.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 全量替换 API 绑定自定义标签（写入 {@code dataapi_api_binding.tags_json}）。
 * 已发布亦可改标签（元数据，不改定义）。
 */
@Getter
@Setter
@Schema(description = "API 绑定标签全量替换")
public class DataapiTagsParam {

    @NotBlank(message = "id 不能为空")
    @Schema(description = "dataapi_api_binding.id")
    private String id;

    @Schema(description = "标签列表（字符串）；null/空=清空")
    private List<String> tags;
}
