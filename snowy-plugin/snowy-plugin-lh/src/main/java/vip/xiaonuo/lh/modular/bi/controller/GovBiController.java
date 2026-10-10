package vip.xiaonuo.lh.modular.bi.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.xiaonuo.common.annotation.CommonLog;
import vip.xiaonuo.common.pojo.CommonResult;
import vip.xiaonuo.lh.modular.bi.param.GovBiDashboardPinParam;
import vip.xiaonuo.lh.modular.bi.param.GovBiEmbedParam;
import vip.xiaonuo.lh.modular.bi.param.GovBiOpenDatasetParam;
import vip.xiaonuo.lh.modular.bi.param.GovBiPublishMetricParam;
import vip.xiaonuo.lh.modular.bi.service.GovBiService;
import vip.xiaonuo.lh.modular.datasource.param.LhDatasourceIdParam;

import java.util.List;
import java.util.Map;

/**
 * BI 门户：投影 / 数据集 / 看板回写 / Guest Token 内嵌 / OIDC 做表
 */
@Tag(name = "BI门户")
@RestController
public class GovBiController {

    @Resource
    private GovBiService govBiService;

    @Operation(summary = "BI 状态")
    @GetMapping("/lh/bi/status")
    public CommonResult<Map<String, Object>> status() {
        return CommonResult.data(govBiService.status());
    }

    @Operation(summary = "Trino→Superset 投影清单")
    @GetMapping("/lh/bi/projection")
    public CommonResult<List<Map<String, Object>>> projection() {
        return CommonResult.data(govBiService.projectionStatus());
    }

    @Operation(summary = "投影 Trino 到 Superset Database")
    @CommonLog("投影数据源到 Superset")
    @PostMapping("/lh/bi/projectDatasources")
    public CommonResult<Map<String, Object>> projectDatasources(
            @RequestBody(required = false) List<LhDatasourceIdParam> ids) {
        return CommonResult.data(govBiService.projectDatasources(ids));
    }

    @Operation(summary = "发布指标为 Superset 虚拟数据集")
    @CommonLog("发布指标数据集到 Superset")
    @PostMapping("/lh/bi/publishMetricDataset")
    public CommonResult<Map<String, Object>> publishMetricDataset(
            @RequestBody @Valid GovBiPublishMetricParam param) {
        return CommonResult.data(govBiService.publishMetricDataset(param));
    }

    @Operation(summary = "已发布指标数据集列表")
    @GetMapping("/lh/bi/datasets")
    public CommonResult<List<Map<String, Object>>> datasets(
            @RequestParam(required = false) String ws) {
        return CommonResult.data(govBiService.listDatasets(ws));
    }

    @Operation(summary = "从 Superset 同步看板元数据")
    @CommonLog("同步 Superset 看板")
    @PostMapping("/lh/bi/syncDashboards")
    public CommonResult<Map<String, Object>> syncDashboards(
            @RequestParam(required = false) String ws) {
        return CommonResult.data(govBiService.syncDashboards(ws));
    }

    @Operation(summary = "门户看板列表")
    @GetMapping("/lh/bi/dashboards")
    public CommonResult<List<Map<String, Object>>> dashboards(
            @RequestParam(required = false) String ws,
            @RequestParam(required = false) Boolean pinnedOnly) {
        return CommonResult.data(govBiService.listDashboards(ws, pinnedOnly));
    }

    @Operation(summary = "置顶/取消置顶看板")
    @CommonLog("置顶 BI 看板")
    @PostMapping("/lh/bi/pinDashboard")
    public CommonResult<Map<String, Object>> pinDashboard(
            @RequestBody @Valid GovBiDashboardPinParam param) {
        return CommonResult.data(govBiService.pinDashboard(param));
    }

    @Operation(summary = "按 filters.dims 重编译刷新数据集 SQL")
    @CommonLog("按维刷新 BI 数据集")
    @PostMapping("/lh/bi/openDatasetWithDims")
    public CommonResult<Map<String, Object>> openDatasetWithDims(
            @RequestBody GovBiOpenDatasetParam param) {
        return CommonResult.data(govBiService.openDatasetWithDims(param));
    }

    @Operation(summary = "签发 Guest Token 内嵌会话（看数）")
    @CommonLog("BI Guest Token 内嵌")
    @PostMapping("/lh/bi/embedSession")
    public CommonResult<Map<String, Object>> embedSession(@RequestBody GovBiEmbedParam param) {
        return CommonResult.data(govBiService.embedSession(param));
    }

    @Operation(summary = "OIDC 做表入口（未启用则失败，无 AUTH_DB 降级）")
    @GetMapping("/lh/bi/authoringEntry")
    public CommonResult<Map<String, Object>> authoringEntry(
            @RequestParam(required = false, defaultValue = "home") String kind,
            @RequestParam(required = false) String id) {
        return CommonResult.data(govBiService.authoringEntry(kind, id));
    }
}
