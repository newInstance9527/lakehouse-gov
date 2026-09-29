package vip.xiaonuo.lh.modular.dataapi.param;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
public class DataapiGatewayProbeParam {
    /** 绑定 id（优先）；与 path 二选一 */
    private String id;
    /** 对外路径，如 /api/demo */
    private String path;
    private String method = "GET";
    /**
     * 在线调试入参：[{name,value|example|defaultValue,type,required}]；
     * GET/DELETE 拼 query，POST/PUT 作 JSON body。
     */
    private List<Map<String, Object>> params;
    /** 订阅 App Key → 请求头 X-App-Key */
    private String appKey;
    /** 订阅 Secret → Authorization: Bearer …（可带或不带 Bearer 前缀） */
    private String bearerToken;
}
