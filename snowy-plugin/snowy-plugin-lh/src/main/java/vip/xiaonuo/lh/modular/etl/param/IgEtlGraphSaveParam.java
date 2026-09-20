package vip.xiaonuo.lh.modular.etl.param;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
public class IgEtlGraphSaveParam {
    @NotBlank(message = "id不能为空")
    private String id;
    private List<Map<String, Object>> nodes;
    private List<Map<String, Object>> edges;
}
