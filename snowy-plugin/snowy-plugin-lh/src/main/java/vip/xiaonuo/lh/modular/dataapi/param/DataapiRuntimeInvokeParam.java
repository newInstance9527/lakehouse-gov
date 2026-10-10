package vip.xiaonuo.lh.modular.dataapi.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * 门户运行时门面调用：订阅 Key 鉴权后注入 row_filter 再 SQLREST debug。
 */
@Getter
@Setter
@Schema(description = "数据服务运行时调用")
public class DataapiRuntimeInvokeParam {

    @Schema(description = "绑定 id；与 path 二选一，须与 Key 绑定一致")
    private String bindingId;

    @Schema(description = "对外 path，如 /api/demo")
    private String path;

    @Schema(description = "HTTP 方法，默认取绑定")
    private String method;

    @Schema(description = "门户入参列表（name/value/type）")
    private List<Map<String, Object>> params;

    @Schema(description = "命名策略，默认 CAMEL_CASE")
    private String namingStrategy;
}
