package vip.xiaonuo.lh.modular.workspace.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 全量替换工作空间标签（写入 {@code gov_ws.tags_json}）。
 */
@Getter
@Setter
@Schema(description = "工作空间标签全量替换")
public class GovWsTagsReplaceParam {

    @Schema(description = "标签列表 [{text,cls}]；null/空=清空")
    private List<GovWsTagAddParam> tags;
}
