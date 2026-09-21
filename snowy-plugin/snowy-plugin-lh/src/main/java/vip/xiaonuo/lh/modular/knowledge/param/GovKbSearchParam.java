package vip.xiaonuo.lh.modular.knowledge.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
public class GovKbSearchParam {

    private String ws;
    private String query;
    private Integer topK;
    private List<String> cats;
    private Map<String, Object> filters;
}
