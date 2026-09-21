package vip.xiaonuo.lh.modular.workspace.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class GovWsMembersReplaceParam {

    @Schema(description = "全量替换成员列表")
    private List<GovWsMemberItemParam> members;
}
