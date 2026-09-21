package vip.xiaonuo.lh.modular.knowledge.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GovKbPageParam {

    private String q;
    private String cat;
    private String ws;
    private String status;
    private String sortField;
    private String sortOrder;
}
