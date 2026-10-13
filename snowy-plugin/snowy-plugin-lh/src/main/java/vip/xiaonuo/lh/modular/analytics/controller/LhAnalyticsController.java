package vip.xiaonuo.lh.modular.analytics.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import vip.xiaonuo.common.pojo.CommonResult;
import vip.xiaonuo.lh.modular.analytics.param.LhAnalyticsCollectParam;
import vip.xiaonuo.lh.modular.analytics.service.LhAnalyticsCollectService;

import java.util.Map;

/**
 * 门户第一方埋点采集 BFF（浏览器 → 本接口 → Kafka）。
 * <p>非多租户采集网关：仅接受配置的 appKey（lakehouse-portal）。</p>
 */
@Tag(name = "门户埋点")
@RestController
@Validated
public class LhAnalyticsController {

    @Resource
    private LhAnalyticsCollectService analyticsCollectService;

    @Operation(summary = "采集门户行为事件批包")
    @PostMapping("/lh/analytics/collect")
    public CommonResult<Map<String, Object>> collect(
            @RequestBody LhAnalyticsCollectParam param,
            @RequestHeader(value = "X-App-Key", required = false) String appKeyHeader) {
        if (param == null) {
            param = new LhAnalyticsCollectParam();
        }
        if ((param.getAppKey() == null || param.getAppKey().isBlank()) && appKeyHeader != null) {
            param.setAppKey(appKeyHeader);
        }
        return CommonResult.data(analyticsCollectService.collect(param));
    }
}
