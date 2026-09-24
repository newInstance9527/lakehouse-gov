package vip.xiaonuo.lh.modular.workspace.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 为工作空间追加一条展示标签（写入 {@code gov_ws.tags_json}）。
 */
@Getter
@Setter
@Schema(description = "工作空间标签新增")
public class GovWsTagAddParam {

    @NotBlank
    @Schema(description = "标签文案", requiredMode = Schema.RequiredMode.REQUIRED)
    private String text;

    @Schema(description = "样式 class：tag-blue/tag-gray/tag-green/tag-orange/tag-red/tag-purple；默认 tag-blue")
    private String cls;
}
