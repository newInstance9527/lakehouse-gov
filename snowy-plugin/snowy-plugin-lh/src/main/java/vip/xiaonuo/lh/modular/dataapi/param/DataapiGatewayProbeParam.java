package vip.xiaonuo.lh.modular.dataapi.param;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DataapiGatewayProbeParam {
    /** 绑定 id（优先）；与 path 二选一 */
    private String id;
    /** 对外路径，如 /api/demo */
    private String path;
    private String method = "GET";
}
