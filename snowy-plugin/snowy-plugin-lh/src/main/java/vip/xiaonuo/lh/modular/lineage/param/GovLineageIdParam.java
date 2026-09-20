package vip.xiaonuo.lh.modular.lineage.param;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GovLineageIdParam {
    @NotBlank(message = "id 不能为空")
    private String id;
}
