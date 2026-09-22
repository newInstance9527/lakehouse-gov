package vip.xiaonuo.lh.modular.lifecycle.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.xiaonuo.common.annotation.CommonLog;
import vip.xiaonuo.common.pojo.CommonResult;
import vip.xiaonuo.lh.modular.lifecycle.service.GovLcStorageService;
import vip.xiaonuo.lh.modular.lifecycle.support.GovLcStorageEventCollect;
import vip.xiaonuo.lh.modular.lifecycle.support.GovLcStorageProfileCollector;

import java.util.List;
import java.util.Map;

/**
 * 存储趋势只读 API（doc/存储趋势.md）
 * <p>
 * 主路径：{@code /lh/lifecycle/storage/*}；兼容：{@code /api/governance/lifecycle/storage/*}
 * <p>
 * 本控制器<strong>不提供</strong> compact / expire 等执行接口，执行复用主台 {@code /lh/lifecycle/*}。
 */
@Tag(name = "存储趋势")
@RestController
@Validated
public class GovLcStorageController {

    @Resource
    private GovLcStorageService govLcStorageService;
    @Resource
    private GovLcStorageProfileCollector profileCollector;
    @Resource
    private GovLcStorageEventCollect eventCollect;

    @Operation(summary = "存储趋势概览（三口径 KPI）")
    @GetMapping({"/lh/lifecycle/storage/summary", "/api/governance/lifecycle/storage/summary"})
    public CommonResult<Map<String, Object>> summary(
            @RequestParam(required = false) String ws,
            @RequestParam(required = false, defaultValue = "30d") String range) {
        return CommonResult.data(govLcStorageService.summary(ws, range));
    }

    @Operation(summary = "存储趋势时序（物理/活跃/可回收 + 变更点）")
    @GetMapping({"/lh/lifecycle/storage/trend", "/api/governance/lifecycle/storage/trend"})
    public CommonResult<Map<String, Object>> trend(
            @RequestParam(required = false) String ws,
            @RequestParam(required = false, defaultValue = "30d") String range,
            @RequestParam(required = false, defaultValue = "layer") String group) {
        return CommonResult.data(govLcStorageService.trend(ws, range, group));
    }

    @Operation(summary = "表级画像（服务端排序分页）")
    @GetMapping({"/lh/lifecycle/storage/tables", "/api/governance/lifecycle/storage/tables"})
    public CommonResult<Map<String, Object>> tables(
            @RequestParam(required = false) String ws,
            @RequestParam(required = false, defaultValue = "30d") String range,
            @RequestParam(required = false) String layer,
            @RequestParam(required = false, defaultValue = "all") String filter,
            @RequestParam(required = false, defaultValue = "reclaimableBytes") String sort,
            @RequestParam(required = false, defaultValue = "desc") String order,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return CommonResult.data(govLcStorageService.tables(ws, range, layer, filter, sort, order, page, size));
    }

    @Operation(summary = "单表明细（FQN 走 query，避免点号截断）")
    @GetMapping({"/lh/lifecycle/storage/tables/detail", "/api/governance/lifecycle/storage/tables/detail"})
    public CommonResult<Map<String, Object>> tableDetail(
            @RequestParam(required = false) String ws,
            @RequestParam("fqtn") String fqtn,
            @RequestParam(required = false, defaultValue = "90d") String range) {
        return CommonResult.data(govLcStorageService.tableDetail(ws, fqtn, range));
    }

    @Operation(summary = "桶水位与 days-to-full")
    @GetMapping({"/lh/lifecycle/storage/buckets", "/api/governance/lifecycle/storage/buckets"})
    public CommonResult<List<Map<String, Object>>> buckets(@RequestParam(required = false) String ws) {
        return CommonResult.data(govLcStorageService.buckets(ws));
    }

    @Operation(summary = "治理建议（按预计可回收排序；动作只深链）")
    @GetMapping({"/lh/lifecycle/storage/advice", "/api/governance/lifecycle/storage/advice"})
    public CommonResult<List<Map<String, Object>>> advice(@RequestParam(required = false) String ws) {
        return CommonResult.data(govLcStorageService.advice(ws));
    }

    @Operation(summary = "按空间 showback（group=ws；配额读 gov_ws_quota；金额引 lh.finops §24.3）")
    @GetMapping({"/lh/lifecycle/storage/showback", "/api/governance/lifecycle/storage/showback"})
    public CommonResult<Map<String, Object>> showback(
            @RequestParam(required = false) String ws,
            @RequestParam(required = false, defaultValue = "30d") String range,
            @RequestParam(required = false, defaultValue = "ws") String group) {
        return CommonResult.data(govLcStorageService.showback(ws, range, group));
    }

    @Operation(summary = "重跑存储画像日批（Trino 元数据 → gov_lc_table_stat + VM lh_table_storage_*）")
    @CommonLog("存储画像日批")
    @PostMapping({"/lh/lifecycle/storage/collect/rerun", "/api/governance/lifecycle/storage/collect/rerun"})
    public CommonResult<Map<String, Object>> collectRerun(@RequestParam(required = false) String ws) {
        return CommonResult.data(profileCollector.runDaily(ws));
    }

    @Operation(summary = "事件驱动采集（仅 L3；Iceberg commit / 表状态变更钩子）")
    @CommonLog("存储画像事件采集")
    @PostMapping({"/lh/lifecycle/storage/collect/on-commit", "/api/governance/lifecycle/storage/collect/on-commit"})
    public CommonResult<Map<String, Object>> collectOnCommit(
            @RequestParam(required = false) String ws,
            @RequestParam String tableFqn,
            @RequestParam(required = false) String eventId) {
        return CommonResult.data(eventCollect.onCommit(ws, tableFqn, eventId));
    }
}
