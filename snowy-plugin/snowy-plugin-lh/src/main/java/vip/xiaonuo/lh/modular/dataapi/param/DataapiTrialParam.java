package vip.xiaonuo.lh.modular.dataapi.param;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
public class DataapiTrialParam {
    private String id;
    private String sql;
    private String method;
    private List<Map<String, Object>> params;
    private Long datasourceId;
    private String ws;
}
