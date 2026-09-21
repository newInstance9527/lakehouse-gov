package vip.xiaonuo.lh.modular.query.controller;

import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vip.xiaonuo.common.annotation.CommonLog;
import vip.xiaonuo.common.pojo.CommonResult;
import vip.xiaonuo.lh.modular.query.param.CpQueryCancelParam;
import vip.xiaonuo.lh.modular.query.param.CpQueryDatasetSaveParam;
import vip.xiaonuo.lh.modular.query.param.CpQueryExecParam;
import vip.xiaonuo.lh.modular.query.param.CpQueryExportParam;
import vip.xiaonuo.lh.modular.query.param.CpQueryHistoryParam;
import vip.xiaonuo.lh.modular.query.service.CpQueryService;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 即席查询 Trino 接入层
 * <p>主路径 {@code /lh/compute/query/*}；兼容 {@code /lh/query/*} 与全栈 {@code /api/compute/query/*}。</p>
 *
 * @author lakehouse
 * @date 2026/9/21
 */
@Tag(name = "即席查询")
@RestController
@Validated
public class CpQueryController {

    private final ExecutorService ssePool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "cp-query-sse");
        t.setDaemon(true);
        return t;
    });

    @Resource
    private CpQueryService cpQueryService;

    @Operation(summary = "执行 SQL")
    @CommonLog("即席查询执行")
    @PostMapping({"/lh/compute/query/exec", "/lh/query/exec", "/api/compute/query/exec"})
    public CommonResult<Map<String, Object>> exec(@RequestBody @Valid CpQueryExecParam param) {
        return CommonResult.data(cpQueryService.exec(param));
    }

    @Operation(summary = "执行 SQL（SSE 进度）")
    @PostMapping(
            value = {
                    "/lh/compute/query/exec-stream",
                    "/lh/query/exec-stream",
                    "/api/compute/query/exec-stream"
            },
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter execStream(@RequestBody @Valid CpQueryExecParam param) {
        SseEmitter emitter = new SseEmitter(5 * 60_000L);
        // 请求线程里取 token；异步线程无 Servlet 上下文，必须自行挂上 SaTokenContext
        final String token = StpUtil.getTokenValue();
        ssePool.execute(() -> {
            SaTokenContextMockUtil.setMockContext();
            try {
                if (StrUtil.isNotBlank(token)) {
                    StpUtil.setTokenValue(token);
                }
                send(emitter, "started", Map.of("ok", true));
                Map<String, Object> result = cpQueryService.exec(param, stage -> {
                    try {
                        send(emitter, "progress", stage);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
                send(emitter, "done", result);
                emitter.complete();
            } catch (Exception e) {
                try {
                    send(emitter, "error", Map.of("message", e.getMessage() == null ? "error" : e.getMessage()));
                    // completeWithError 不结束 chunked 响应，浏览器已收到 error 仍会挂住
                    emitter.complete();
                } catch (Exception ignored) {
                    emitter.completeWithError(e);
                }
            } finally {
                SaTokenContextMockUtil.clearContext();
            }
        });
        return emitter;
    }

    @Operation(summary = "取消查询")
    @CommonLog("即席查询取消")
    @PostMapping({"/lh/compute/query/cancel", "/lh/query/cancel", "/api/compute/query/cancel"})
    public CommonResult<Map<String, Object>> cancel(@RequestBody CpQueryCancelParam param) {
        return CommonResult.data(cpQueryService.cancel(param));
    }

    @Operation(summary = "查询历史")
    @GetMapping({"/lh/compute/query/history", "/lh/query/history", "/api/compute/query/history"})
    public CommonResult<List<Map<String, Object>>> history(CpQueryHistoryParam param) {
        return CommonResult.data(cpQueryService.history(param));
    }

    @Operation(summary = "即席目录（门户资产；不列举 Gravitino/Trino）")
    @GetMapping({
            "/lh/compute/query/schema-tree",
            "/lh/compute/query/schemaTree",
            "/lh/query/schemaTree",
            "/lh/query/schema-tree",
            "/api/compute/query/schema-tree"
    })
    public CommonResult<List<Map<String, Object>>> schemaTree(
            @RequestParam(required = false) String ws) {
        return CommonResult.data(cpQueryService.schemaTree(ws));
    }

    @Operation(summary = "表列懒加载（门户列快照；漂移时按指针回源一次）")
    @GetMapping({
            "/lh/compute/query/columns",
            "/lh/query/columns",
            "/api/compute/query/columns"
    })
    public CommonResult<Map<String, Object>> columns(
            @RequestParam(required = false) String assetId,
            @RequestParam(required = false) String fqn) {
        return CommonResult.data(cpQueryService.tableColumns(assetId, fqn));
    }

    @Operation(summary = "导出脱敏 CSV 审计登记")
    @CommonLog("即席查询导出")
    @PostMapping({"/lh/compute/query/export", "/lh/query/export", "/api/compute/query/export"})
    public CommonResult<Map<String, Object>> export(@RequestBody CpQueryExportParam param) {
        return CommonResult.data(cpQueryService.exportAudit(param));
    }

    @Operation(summary = "查询治理总览")
    @GetMapping({
            "/lh/compute/query/gov/overview",
            "/lh/query/gov/overview",
            "/api/compute/query/gov/overview"
    })
    public CommonResult<Map<String, Object>> govOverview() {
        return CommonResult.data(cpQueryService.govOverview());
    }

    @Operation(summary = "EXPLAIN 计划")
    @PostMapping({"/lh/compute/query/explain", "/lh/query/explain", "/api/compute/query/explain"})
    public CommonResult<Map<String, Object>> explain(@RequestBody @Valid CpQueryExecParam param) {
        return CommonResult.data(cpQueryService.explain(param));
    }

    @Operation(summary = "探测命名参数")
    @PostMapping({"/lh/compute/query/detect-params", "/lh/query/detect-params", "/api/compute/query/detect-params"})
    public CommonResult<Map<String, Object>> detectParams(@RequestBody Map<String, String> body) {
        return CommonResult.data(cpQueryService.detectParams(body == null ? null : body.get("sql")));
    }

    @Operation(summary = "保存抽样数据集")
    @CommonLog("即席保存数据集")
    @PostMapping({"/lh/compute/query/datasets", "/lh/query/datasets", "/api/compute/query/datasets"})
    public CommonResult<Map<String, Object>> saveDataset(@RequestBody CpQueryDatasetSaveParam param) {
        return CommonResult.data(cpQueryService.saveDataset(param));
    }

    @Operation(summary = "我的数据集列表")
    @GetMapping({"/lh/compute/query/datasets", "/lh/query/datasets", "/api/compute/query/datasets"})
    public CommonResult<List<Map<String, Object>>> listDatasets(
            @RequestParam(required = false) String ws,
            @RequestParam(required = false) Integer limit) {
        return CommonResult.data(cpQueryService.listDatasets(ws, limit));
    }

    @Operation(summary = "即席查询面（白名单 ∩ SHOW CATALOGS）")
    @GetMapping({
            "/lh/compute/query/query-surface",
            "/lh/query/query-surface",
            "/api/compute/query/query-surface"
    })
    public CommonResult<Map<String, Object>> querySurface() {
        return CommonResult.data(cpQueryService.querySurface());
    }

    @Operation(summary = "Grav→Trino catalog 映射列表")
    @GetMapping({
            "/lh/compute/query/catalog-map",
            "/lh/query/catalog-map",
            "/api/compute/query/catalog-map"
    })
    public CommonResult<List<Map<String, Object>>> listCatalogMaps(
            @RequestParam(required = false) String ws) {
        return CommonResult.data(cpQueryService.listCatalogMaps(ws));
    }

    @Operation(summary = "联邦源开通：登记/更新 Grav→Trino 映射")
    @CommonLog("即席联邦源开通")
    @PostMapping({
            "/lh/compute/query/catalog-map",
            "/lh/query/catalog-map",
            "/api/compute/query/catalog-map"
    })
    public CommonResult<Map<String, Object>> upsertCatalogMap(@RequestBody Map<String, Object> body) {
        return CommonResult.data(cpQueryService.upsertCatalogMap(body));
    }

    private static void send(SseEmitter emitter, String event, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(event).data(data));
    }
}
