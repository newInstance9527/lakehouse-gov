package vip.xiaonuo.lh.modular.sec.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LhTrinoPrincipalBindMeParam {

    @NotBlank
    @Schema(description = "Gravitino/Trino 中已存在的人类主体，禁止填服务账号 admin")
    private String trinoUser;

    @Schema(description = "备注")
    private String remark;
}
