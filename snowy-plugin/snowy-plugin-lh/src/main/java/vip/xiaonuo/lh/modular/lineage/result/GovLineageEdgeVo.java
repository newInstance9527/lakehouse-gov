package vip.xiaonuo.lh.modular.lineage.result;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GovLineageEdgeVo {
    private String id;
    private String fromTable;
    private String fromField;
    private String toTable;
    private String toField;
    private String transform;
    private String confidence;
    private String etlJobId;
    private String omFromFqn;
    private String omToFqn;
    private String status;
}
