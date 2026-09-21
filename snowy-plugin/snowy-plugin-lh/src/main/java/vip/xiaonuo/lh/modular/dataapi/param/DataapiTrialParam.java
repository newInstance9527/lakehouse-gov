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
    /** SQLREST 数据源 id（已知时） */
    private Long datasourceId;
    /** 门户数据源 id；优先投影解析为 SQLREST datasourceId */
    private String portalDsId;
    private String dsId;
    /** SQL / GROOVY */
    private String engine = "SQL";
    private String namingStrategy;
    private List<Map<String, Object>> formatMap;
    private List<String> contextList;
    private String ws;
}
