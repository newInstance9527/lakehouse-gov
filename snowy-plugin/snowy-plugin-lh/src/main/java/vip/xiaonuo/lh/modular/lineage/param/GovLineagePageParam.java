package vip.xiaonuo.lh.modular.lineage.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GovLineagePageParam {
    private String q;
    private String keyword;
    private String ws;
    private String focusTable;
    private String focusField;
    private String sortField;
    private String sortOrder;
}
