package vip.xiaonuo.lh.modular.quality.param;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class GovDqIdParam {
    @NotBlank(message = "id 不能为空")
    private String id;
}
