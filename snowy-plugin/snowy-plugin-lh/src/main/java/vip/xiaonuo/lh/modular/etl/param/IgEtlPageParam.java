package vip.xiaonuo.lh.modular.etl.param;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class IgEtlPageParam {
    private String q;
    private String keyword;
    private String ws;
    /** workspace|all；缺省 workspace（须配 ws） */
    private String scope;
    private String status;
    private String sortField;
    private String sortOrder;
}
