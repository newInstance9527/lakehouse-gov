package vip.xiaonuo.lh.modular.dataapi.param;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DataapiIdParam {
    @NotBlank(message = "id 不能为空")
    private String id;
    private String ws;
}
