package vip.xiaonuo.lh.modular.etl.param;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class IgEtlTrialParam {
    @NotBlank(message = "id不能为空")
    private String id;
    private String env;
}
