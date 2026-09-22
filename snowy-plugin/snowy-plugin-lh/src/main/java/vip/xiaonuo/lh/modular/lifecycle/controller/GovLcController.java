package vip.xiaonuo.lh.modular.lifecycle.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import vip.xiaonuo.common.annotation.CommonLog;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.common.pojo.CommonResult;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.modular.lifecycle.support.GovLcCallbackAuth;
import vip.xiaonuo.lh.modular.lifecycle.param.GovLcOrphanScanParam;
import vip.xiaonuo.lh.modular.lifecycle.param.GovLcPolicyUpsertParam;
import vip.xiaonuo.lh.modular.lifecycle.param.GovLcRunNowParam;
import vip.xiaonuo.lh.modular.lifecycle.param.GovLcRunPageParam;
import vip.xiaonuo.lh.modular.lifecycle.param.GovLcTableActionParam;
import vip.xiaonuo.lh.modular.lifecycle.result.GovLcPolicyVo;
import vip.xiaonuo.lh.modular.lifecycle.result.GovLcRunVo;
import vip.xiaonuo.lh.modular.lifecycle.service.GovLcService;

import java.util.List;
import java.util.Map;

/**
 * 生命周期与小文件治理（doc/生命周期.md）
 * <p>
 * 主路径：{@code /lh/lifecycle/*}；兼容：{@code /api/governance/lifecycle/*}
 */
@Tag(name = "生命周期与小文件治理")
@RestController
@Validated
public class GovLcController {

    @Resource
    private GovLcService govLcService;
    @Resource
    private LhProperties lhProperties;

    @Operation(summary = "KPI 概览")
    @GetMapping({"/lh/lifecycle/overview", "/api/governance/lifecycle/overview"})
    public CommonResult<Map<String, Object>> overview(@RequestParam(required = false) String ws) {
        return CommonResult.data(govLcService.overview(ws));
    }

    @Operation(summary = "最近日作业四步")
    @GetMapping({"/lh/lifecycle/jobs/latest", "/api/governance/lifecycle/jobs/latest"})
    public CommonResult<Map<String, Object>> latestJobs(@RequestParam(required = false) String ws) {
        return CommonResult.data(govLcService.latestJobs(ws));
    }

    @Operation(summary = "表存储 Top")
    @GetMapping({"/lh/lifecycle/top-storage", "/api/governance/lifecycle/top-storage"})
    public CommonResult<List<Map<String, Object>>> topStorage(
            @RequestParam(required = false) String ws,
            @RequestParam(required = false) Integer limit) {
        return CommonResult.data(govLcService.topStorage(ws, limit));
    }

    @Operation(summary = "单表快照/小文件/策略指标")
    @GetMapping({"/lh/lifecycle/stats", "/api/governance/lifecycle/stats"})
    public CommonResult<Map<String, Object>> stats(
            @RequestParam(required = false) String ws,
            @RequestParam("table") String table) {
        return CommonResult.data(govLcService.stats(ws, table));
    }

    @Operation(summary = "策略列表")
    @GetMapping({"/lh/lifecycle/policies", "/api/governance/lifecycle/policies"})
    public CommonResult<List<GovLcPolicyVo>> listPolicies(@RequestParam(required = false) String ws) {
        return CommonResult.data(govLcService.listPolicies(ws));
    }

    @Operation(summary = "按表读策略")
    @GetMapping({"/lh/lifecycle/policy", "/api/governance/lifecycle/policy"})
    public CommonResult<GovLcPolicyVo> getPolicy(
            @RequestParam(required = false) String ws,
            @RequestParam("table") String table) {
        return CommonResult.data(govLcService.getPolicy(ws, table));
    }

    @Operation(summary = "保存/更新策略")
    @CommonLog("生命周期策略保存")
    @PutMapping({"/lh/lifecycle/policies", "/api/governance/lifecycle/policies"})
    public CommonResult<GovLcPolicyVo> upsertPolicy(@RequestBody @Valid GovLcPolicyUpsertParam param) {
        return CommonResult.data(govLcService.upsertPolicy(param));
    }

    @Operation(summary = "兼容 §36.2：按表写策略")
    @CommonLog("生命周期策略保存")
    @PutMapping({"/lh/lifecycle/policy", "/api/governance/lifecycle/policy"})
    public CommonResult<GovLcPolicyVo> upsertPolicyAlias(
            @RequestParam("table") String table,
            @RequestBody @Valid GovLcPolicyUpsertParam param) {
        param.setTableFqn(table);
        return CommonResult.data(govLcService.upsertPolicy(param));
    }

    @Operation(summary = "触发小文件合并")
    @CommonLog("生命周期 compact")
    @PostMapping({"/lh/lifecycle/compact", "/api/governance/lifecycle/compact"})
    public CommonResult<GovLcRunVo> compact(@RequestBody @Valid GovLcTableActionParam param) {
        return CommonResult.data(govLcService.compact(param));
    }

    @Operation(summary = "触发快照过期")
    @CommonLog("生命周期 expire")
    @PostMapping({"/lh/lifecycle/expire", "/api/governance/lifecycle/expire"})
    public CommonResult<GovLcRunVo> expire(@RequestBody @Valid GovLcTableActionParam param) {
        return CommonResult.data(govLcService.expire(param));
    }

    @Operation(summary = "合规 Iceberg 硬删独立 DAG（delete→compact→定向 expire）")
    @CommonLog("合规硬删 Iceberg DAG")
    @PostMapping({"/lh/lifecycle/compliance-delete", "/api/governance/lifecycle/compliance-delete"})
    public CommonResult<GovLcRunVo> complianceDelete(@RequestBody @Valid GovLcTableActionParam param) {
        return CommonResult.data(govLcService.complianceDeleteIceberg(param));
    }

    @Operation(summary = "兼容路径：/{table}/compact（table 用 query 传，避免 FQN 点号截断）")
    @CommonLog("生命周期 compact")
    @PostMapping({"/lh/lifecycle/actions/compact", "/api/governance/lifecycle/actions/compact"})
    public CommonResult<GovLcRunVo> compactAction(
            @RequestParam("table") String table,
            @RequestParam(required = false) String ws,
            @RequestBody(required = false) GovLcTableActionParam body) {
        GovLcTableActionParam param = body != null ? body : new GovLcTableActionParam();
        param.setTableFqn(table);
        if (ws != null) {
            param.setWs(ws);
        }
        return CommonResult.data(govLcService.compact(param));
    }

    @Operation(summary = "兼容路径：actions/expire")
    @CommonLog("生命周期 expire")
    @PostMapping({"/lh/lifecycle/actions/expire", "/api/governance/lifecycle/actions/expire"})
    public CommonResult<GovLcRunVo> expireAction(
            @RequestParam("table") String table,
            @RequestParam(required = false) String ws,
            @RequestBody(required = false) GovLcTableActionParam body) {
        GovLcTableActionParam param = body != null ? body : new GovLcTableActionParam();
        param.setTableFqn(table);
        if (ws != null) {
            param.setWs(ws);
        }
        return CommonResult.data(govLcService.expire(param));
    }

    @Operation(summary = "孤儿扫描（默认 dry-run）")
    @CommonLog("生命周期 orphan scan")
    @PostMapping({"/lh/lifecycle/orphan/scan", "/api/governance/lifecycle/orphan/scan"})
    public CommonResult<Map<String, Object>> orphanScan(@RequestBody(required = false) GovLcOrphanScanParam param) {
        return CommonResult.data(govLcService.orphanScan(param != null ? param : new GovLcOrphanScanParam()));
    }

    @Operation(summary = "立即执行日作业（DS 模板 job.iceberg.lifecycle）")
    @CommonLog("生命周期 run-now")
    @PostMapping({"/lh/lifecycle/jobs/run-now", "/api/governance/lifecycle/jobs/run-now"})
    public CommonResult<GovLcRunVo> runNow(@RequestBody(required = false) GovLcRunNowParam param) {
        return CommonResult.data(govLcService.runNow(param != null ? param : new GovLcRunNowParam()));
    }

    // 存储趋势完整 API 见 GovLcStorageController：/lh/lifecycle/storage/*

    @Operation(summary = "运行留痕分页")
    @GetMapping({"/lh/lifecycle/runs", "/api/governance/lifecycle/runs"})
    public CommonResult<Page<GovLcRunVo>> runs(GovLcRunPageParam param) {
        return CommonResult.data(govLcService.pageRuns(param));
    }

    @Operation(summary = "从 DS 回写运行状态（门户主动拉）")
    @PostMapping({"/lh/lifecycle/runs/sync", "/api/governance/lifecycle/runs/sync"})
    public CommonResult<GovLcRunVo> syncRun(@RequestParam("runId") String runId) {
        return CommonResult.data(govLcService.syncRun(runId));
    }

    @Operation(summary = "DS/Worker 推送回调回写运行状态（J6）")
    @CommonLog("生命周期运行回调")
    @PostMapping({"/lh/lifecycle/runs/callback", "/api/governance/lifecycle/runs/callback"})
    public CommonResult<GovLcRunVo> runCallback(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = GovLcCallbackAuth.HEADER, required = false) String callbackToken) {
        assertCallbackAllowed(callbackToken);
        return CommonResult.data(govLcService.applyRunCallback(body));
    }

    /**
     * 放行条件：共享 token 匹配，或已登录门户用户（联调兜底）。
     */
    private void assertCallbackAllowed(String presentedToken) {
        String configured = lhProperties.getLifecycle() != null
                ? lhProperties.getLifecycle().getCallbackToken() : null;
        if (GovLcCallbackAuth.tokenMatches(configured, presentedToken)) {
            return;
        }
        try {
            if (StpUtil.isLogin()) {
                return;
            }
        } catch (Exception ignored) {
            /* not login */
        }
        if (StrUtil.isBlank(configured)) {
            throw new CommonException("生命周期回调未配置 token，且当前未登录");
        }
        throw new CommonException("生命周期回调鉴权失败（检查 " + GovLcCallbackAuth.HEADER + "）");
    }
}
