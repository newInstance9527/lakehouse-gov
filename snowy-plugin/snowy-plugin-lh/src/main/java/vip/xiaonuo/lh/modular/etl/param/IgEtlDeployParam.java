package vip.xiaonuo.lh.modular.etl.param;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class IgEtlDeployParam {
    @NotBlank(message = "id不能为空")
    private String id;
    private String gitRef;
}
