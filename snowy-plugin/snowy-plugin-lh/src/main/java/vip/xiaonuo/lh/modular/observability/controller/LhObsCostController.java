package vip.xiaonuo.lh.modular.observability.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.xiaonuo.common.pojo.CommonResult;
import vip.xiaonuo.lh.modular.observability.service.LhObsCostService;
import vip.xiaonuo.lh.modular.observability.service.LhObsInfraService;
import vip.xiaonuo.lh.modular.observability.service.LhObsRootcauseService;
import vip.xiaonuo.lh.modular.observability.service.LhObsSpanService;
import vip.xiaonuo.lh.modular.observability.service.LhObsTasksService;
import vip.xiaonuo.lh.modular.observability.service.LhObsTrinoService;

import java.util.Map;

/**
 * 可观测成本 / 用量 / 链路 span / 基础设施 / 根因 / 任务运维 / Trino（§12 · §24 · §29 · §30 · §6.8）
 */
@Tag(name = "可观测")
@RestController
@Validated
public class LhObsCostController {

    @Resource
    private LhObsCostService lhObsCostService;
    @Resource
    private LhObsSpanService lhObsSpanService;
    @Resource
    private LhObsInfraService lhObsInfraService;
    @Resource
    private LhObsRootcauseService lhObsRootcauseService;
    @Resource
    private LhObsTasksService lhObsTasksService;
    @Resource
    private LhObsTrinoService lhObsTrinoService;

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

    @Operation(summary = "基础设施摘要（无采集合法空态）")
    @GetMapping({"/lh/observability/infra/summary", "/api/observability/infra/summary"})
    public CommonResult<Map<String, Object>> infraSummary(@RequestParam(required = false) String ws) {
        return CommonResult.data(lhObsInfraService.summary(ws));
    }

    @Operation(summary = "基础设施节点列表")
    @GetMapping({"/lh/observability/infra/nodes", "/api/observability/infra/nodes"})
    public CommonResult<Map<String, Object>> infraNodes(@RequestParam(required = false) String ws) {
        return CommonResult.data(lhObsInfraService.nodes(ws));
    }

    @Operation(summary = "平台组件进程健康")
    @GetMapping({"/lh/observability/infra/procs", "/api/observability/infra/procs"})
    public CommonResult<Map<String, Object>> infraProcs(@RequestParam(required = false) String ws) {
        return CommonResult.data(lhObsInfraService.procs(ws));
    }

    @Operation(summary = "基础设施告警事件")
    @GetMapping({
            "/lh/observability/infra/alerts",
            "/lh/observability/alerts/events",
            "/api/observability/infra/alerts"
    })
    public CommonResult<Map<String, Object>> infraAlerts(@RequestParam(required = false) String ws) {
        return CommonResult.data(lhObsInfraService.alerts(ws));
    }

    @Operation(summary = "根因告警焦点列表（空列表合法）")
    @GetMapping({"/lh/observability/rootcause/alerts", "/api/observability/rootcause/alerts"})
    public CommonResult<Map<String, Object>> rootcauseAlerts(@RequestParam(required = false) String ws) {
        return CommonResult.data(lhObsRootcauseService.alerts(ws));
    }

    @Operation(summary = "根因最小编排（无证据空态，禁止演示卡片）")
    @PostMapping({"/lh/observability/rootcause/analyze", "/api/observability/rootcause/analyze"})
    public CommonResult<Map<String, Object>> rootcauseAnalyze(@RequestBody(required = false) Map<String, Object> body) {
        return CommonResult.data(lhObsRootcauseService.analyze(body));
    }

    @Operation(summary = "根因结论 + AI diagnose 钩子")
    @PostMapping({"/lh/observability/rootcause/conclusion", "/api/observability/rootcause/conclusion"})
    public CommonResult<Map<String, Object>> rootcauseConclusion(@RequestBody(required = false) Map<String, Object> body) {
        return CommonResult.data(lhObsRootcauseService.conclusion(body));
    }

    @Operation(summary = "根因一键处置（补数 + 工单提示）")
    @PostMapping({"/lh/observability/rootcause/remediate", "/api/observability/rootcause/remediate"})
    public CommonResult<Map<String, Object>> rootcauseRemediate(@RequestBody(required = false) Map<String, Object> body) {
        return CommonResult.data(lhObsRootcauseService.remediate(body));
    }

    @Operation(summary = "任务运维门面（聚合 ETL/recon）")
    @GetMapping({"/lh/observability/tasks", "/api/observability/tasks"})
    public CommonResult<Map<String, Object>> tasks(
            @RequestParam(required = false, defaultValue = "all") String group,
            @RequestParam(required = false) String ws) {
        return CommonResult.data(lhObsTasksService.listTasks(group, ws));
    }

    @Operation(summary = "任务动作 pause/resume/rerun/backfill → ETL 官方口")
    @PostMapping({"/lh/observability/tasks/{id}/action", "/api/observability/tasks/{id}/action"})
    public CommonResult<Map<String, Object>> taskAction(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        return CommonResult.data(lhObsTasksService.action(id, body));
    }

    @Operation(summary = "按血缘重跑下游补数工单")
    @PostMapping({
            "/lh/observability/tasks/{id}/rerun-downstream",
            "/api/observability/tasks/{id}/rerun-downstream"
    })
    public CommonResult<Map<String, Object>> rerunDownstream(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        return CommonResult.data(lhObsTasksService.rerunDownstream(id, body));
    }

    @Operation(summary = "SLA 总览 + 日志深链")
    @GetMapping({"/lh/observability/sla/summary", "/api/observability/sla/summary"})
    public CommonResult<Map<String, Object>> slaSummary(@RequestParam(required = false) String ws) {
        return CommonResult.data(lhObsTasksService.slaSummary(ws));
    }

    @Operation(summary = "span 写入（ingest）")
    @PostMapping({"/lh/observability/spans/ingest", "/api/observability/spans/ingest"})
    public CommonResult<Map<String, Object>> spansIngest(@RequestBody(required = false) Map<String, Object> body) {
        return CommonResult.data(lhObsSpanService.ingest(body));
    }

    @Operation(summary = "Trino 队列门面")
    @GetMapping({"/lh/observability/trino/queues", "/api/observability/trino/queues"})
    public CommonResult<Map<String, Object>> trinoQueues(@RequestParam(required = false) String ws) {
        return CommonResult.data(lhObsTrinoService.queues(ws));
    }

    @Operation(summary = "Trino Top 用户")
    @GetMapping({"/lh/observability/trino/top-users", "/api/observability/trino/top-users"})
    public CommonResult<Map<String, Object>> trinoTopUsers(
            @RequestParam(required = false) String ws,
            @RequestParam(required = false, defaultValue = "30d") String range) {
        return CommonResult.data(lhObsTrinoService.topUsers(ws, range));
    }

    @Operation(summary = "Trino 慢查询")
    @GetMapping({"/lh/observability/trino/slow", "/api/observability/trino/slow"})
    public CommonResult<Map<String, Object>> trinoSlow(
            @RequestParam(required = false) String ws,
            @RequestParam(required = false, defaultValue = "30d") String range,
            @RequestParam(required = false) Integer limit) {
        return CommonResult.data(lhObsTrinoService.slow(ws, range, limit));
    }

    @Operation(summary = "L1 容器（二期空态）")
    @GetMapping({"/lh/observability/infra/containers", "/api/observability/infra/containers"})
    public CommonResult<Map<String, Object>> infraContainers(@RequestParam(required = false) String ws) {
        return CommonResult.data(lhObsInfraService.containers(ws));
    }

    @Operation(summary = "L2 集群对象（二期空态）")
    @GetMapping({"/lh/observability/infra/cluster", "/api/observability/infra/cluster"})
    public CommonResult<Map<String, Object>> infraCluster(@RequestParam(required = false) String ws) {
        return CommonResult.data(lhObsInfraService.cluster(ws));
    }

    @Operation(summary = "容量预测（二期空态）")
    @GetMapping({"/lh/observability/infra/capacity-forecast", "/api/observability/infra/capacity-forecast"})
    public CommonResult<Map<String, Object>> infraCapacityForecast(@RequestParam(required = false) String ws) {
        return CommonResult.data(lhObsInfraService.capacityForecast(ws));
    }
}
