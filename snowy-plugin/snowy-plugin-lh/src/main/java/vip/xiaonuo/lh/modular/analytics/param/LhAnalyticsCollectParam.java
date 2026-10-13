package vip.xiaonuo.lh.modular.analytics.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * 门户埋点批包（对齐 sdks/web-analytics PROTOCOL）。
 */
@Getter
@Setter
public class LhAnalyticsCollectParam {

    @Schema(description = "应用键，须为 lakehouse-portal")
    private String appKey;

    @Schema(description = "客户端发送时间 ISO-8601")
    private String sentAt;

    @Schema(description = "事件列表")
    private List<Map<String, Object>> events;
}
