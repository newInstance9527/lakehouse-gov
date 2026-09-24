package vip.xiaonuo.lh.modular.observability.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.xiaonuo.common.pojo.CommonResult;
import vip.xiaonuo.lh.modular.observability.service.LhObsCostService;
import vip.xiaonuo.lh.modular.observability.service.LhObsSpanService;

import java.util.Map;

/**
 * 可观测成本 / 用量 / 链路 span（§24.3 · §29）
 */
@Tag(name = "可观测")
@RestController
@Validated
public class LhObsCostController {

    @Resource
    private LhObsCostService lhObsCostService;
    @Resource
    private LhObsSpanService lhObsSpanService;

    @Operation(summary = "用量/成本按 group 聚合（验收 group=ws）")
    @GetMapping({
            "/lh/observability/costs",
            "/api/observability/costs",
            "/lh/compute/query/gov/costs",
            "/lh/query/gov/costs"
    })
    public CommonResult<Map<String, Object>> costs(
            @RequestParam(required = false, defaultValue = "30d") String range,
            @RequestParam(required = false, defaultValue = "ws") String group,
            @RequestParam(required = false) String ws) {
        return CommonResult.data(lhObsCostService.costs(range, group, ws));
    }

    @Operation(summary = "用量按 group 聚合（与 costs 同源）")
    @GetMapping({
            "/lh/observability/usage",
            "/api/observability/usage"
    })
    public CommonResult<Map<String, Object>> usage(
            @RequestParam(required = false, defaultValue = "30d") String range,
            @RequestParam(required = false, defaultValue = "ws") String group,
            @RequestParam(required = false) String ws) {
        return CommonResult.data(lhObsCostService.costs(range, group, ws));
    }

    @Operation(summary = "A–L 链路总览")
    @GetMapping("/lh/observability/links/overview")
    public CommonResult<Map<String, Object>> linksOverview(@RequestParam(required = false) String ws) {
        return CommonResult.data(lhObsSpanService.linksOverview(ws));
    }

    @Operation(summary = "span 列表")
    @GetMapping("/lh/observability/spans")
    public CommonResult<Map<String, Object>> spans(
            @RequestParam(required = false) String ws,
            @RequestParam(required = false) String linkId,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String runId,
            @RequestParam(required = false) String eventId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "1") long current,
            @RequestParam(required = false, defaultValue = "50") long size) {
        return CommonResult.data(lhObsSpanService.listSpans(ws, linkId, traceId, runId, eventId, status, current, size));
    }

    @Operation(summary = "按 trace 瀑布")
    @GetMapping("/lh/observability/traces/{traceId}")
    public CommonResult<Map<String, Object>> trace(
            @PathVariable String traceId,
            @RequestParam(required = false) String ws) {
        return CommonResult.data(lhObsSpanService.trace(traceId, ws));
    }

    @Operation(summary = "日志检索（P0 回落 span）")
    @GetMapping("/lh/observability/logs/search")
    public CommonResult<Map<String, Object>> logs(
            @RequestParam(required = false) String ws,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String runId,
            @RequestParam(required = false) String eventId,
            @RequestParam(required = false, defaultValue = "1") long current,
            @RequestParam(required = false, defaultValue = "50") long size) {
        return CommonResult.data(lhObsSpanService.searchLogs(ws, q, traceId, runId, eventId, current, size));
    }

    @Operation(summary = "失败 span 下钻")
    @GetMapping("/lh/observability/spans/{spanId}/error")
    public CommonResult<Map<String, Object>> spanError(
            @PathVariable String spanId,
            @RequestParam(required = false) String ws) {
        return CommonResult.data(lhObsSpanService.spanError(spanId, ws));
    }
}
