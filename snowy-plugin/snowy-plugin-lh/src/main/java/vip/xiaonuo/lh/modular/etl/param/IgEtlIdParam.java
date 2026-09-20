package vip.xiaonuo.lh.modular.etl.param;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class IgEtlIdParam {
    @NotBlank(message = "id不能为空")
    private String id;
}
