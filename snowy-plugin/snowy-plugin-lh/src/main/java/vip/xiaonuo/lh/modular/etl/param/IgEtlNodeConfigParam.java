package vip.xiaonuo.lh.modular.etl.param;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
public class IgEtlNodeConfigParam {
    @NotBlank(message = "dagId不能为空")
    private String dagId;
    @NotBlank(message = "nodeKey不能为空")
    private String nodeKey;
    private String name;
    private String meta;
    private Map<String, Object> conf;
    private String confJson;
}
