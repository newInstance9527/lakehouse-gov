package vip.xiaonuo.lh.modular.recon.controller;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.xiaonuo.common.annotation.CommonLog;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.common.pojo.CommonResult;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.modular.recon.support.GovReconCallbackAuth;
import vip.xiaonuo.lh.modular.recon.support.ReconRuleService;

import java.util.Map;

/**
 * 可靠性对账规则 / 差异 / 黄金摘牌（§33）。
 * 主路径 {@code /lh/recon/*}；兼容 {@code /lh/governance/reconcile/*}、{@code /api/governance/reconcile/*}。
 */
@Tag(name = "可靠性对账")
@RestController
@Validated
public class LhReconController {

    @Resource
    private ReconRuleService reconRuleService;
    @Resource
    private LhProperties lhProperties;

    @Operation(summary = "对账规则列表（空列表合法）")
    @GetMapping({
            "/lh/recon/rules",
            "/lh/governance/reconcile/rules",
            "/api/governance/reconcile/rules"
    })
    public CommonResult<Map<String, Object>> listRules(
            @RequestParam(required = false) String ws,
            @RequestParam(required = false) String ruleType,
            @RequestParam(required = false) Boolean enabled) {
        return CommonResult.data(reconRuleService.listRules(ws, ruleType, enabled));
    }

    @Operation(summary = "对账规则创建/更新")
    @CommonLog("对账规则 upsert")
    @PostMapping({
            "/lh/recon/rules",
            "/lh/governance/reconcile/rules",
            "/api/governance/reconcile/rules"
    })
    public CommonResult<Map<String, Object>> upsertRule(@RequestBody Map<String, Object> body) {
        return CommonResult.data(reconRuleService.upsertRule(body));
    }

    @Operation(summary = "对账规则软删除")
    @CommonLog("对账规则删除")
    @PostMapping({
            "/lh/recon/rules/delete",
            "/lh/governance/reconcile/rules/delete",
            "/api/governance/reconcile/rules/delete"
    })
    public CommonResult<Map<String, Object>> deleteRule(@RequestBody Map<String, Object> body) {
        Object id = body != null ? body.get("id") : null;
        return CommonResult.data(reconRuleService.deleteRule(id == null ? null : String.valueOf(id)));
    }

    @Operation(summary = "对账差异下钻列表")
    @GetMapping({
            "/lh/recon/diff",
            "/lh/governance/reconcile/diff",
            "/api/governance/reconcile/diff"
    })
    public CommonResult<Map<String, Object>> listDiff(
            @RequestParam(required = false) String ws,
            @RequestParam(required = false) String lakeTable,
            @RequestParam(required = false) String table,
            @RequestParam(required = false) String partitionKey,
            @RequestParam(required = false) String dt,
            @RequestParam(required = false) String ruleId,
            @RequestParam(required = false) Integer limit) {
        String lake = StrUtil.blankToDefault(lakeTable, table);
        String part = partitionKey;
        if (StrUtil.isBlank(part) && StrUtil.isNotBlank(dt)) {
            part = dt.startsWith("dt=") ? dt : "dt=" + dt;
        }
        return CommonResult.data(reconRuleService.listDiff(ws, lake, part, ruleId, limit));
    }

    @Operation(summary = "登记对账差异")
    @CommonLog("对账差异登记")
    @PostMapping({
            "/lh/recon/diff",
            "/lh/governance/reconcile/diff",
            "/api/governance/reconcile/diff"
    })
    public CommonResult<Map<String, Object>> recordDiff(@RequestBody Map<String, Object> body) {
        return CommonResult.data(reconRuleService.recordDiff(body));
    }

    @Operation(summary = "黄金摘牌/恢复/重导 CK")
    @CommonLog("黄金摘牌动作")
    @PostMapping({
            "/lh/recon/golden/{action}",
            "/lh/governance/reconcile/golden/{action}",
            "/api/governance/reconcile/golden/{action}"
    })
    public CommonResult<Map<String, Object>> goldenPathAction(
            @PathVariable("action") String action,
            @RequestBody(required = false) Map<String, Object> body) {
        return CommonResult.data(invokeGolden(action, body));
    }

    @Operation(summary = "黄金摘牌动作（body.action）")
    @CommonLog("黄金摘牌动作")
    @PostMapping({
            "/lh/recon/golden",
            "/lh/governance/reconcile/golden",
            "/api/governance/reconcile/golden"
    })
    public CommonResult<Map<String, Object>> goldenBodyAction(@RequestBody Map<String, Object> body) {
        Object action = body != null ? body.get("action") : null;
        return CommonResult.data(invokeGolden(action == null ? null : String.valueOf(action), body));
    }

    @Operation(summary = "手动重导 CK（表路径）")
    @CommonLog("对账重导 CK")
    @PostMapping({
            "/lh/recon/{table}/rewrite-ck",
            "/lh/governance/reconcile/{table}/rewrite-ck",
            "/api/governance/reconcile/{table}/rewrite-ck"
    })
    public CommonResult<Map<String, Object>> rewriteCk(
            @PathVariable("table") String table,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> param = body == null ? Map.of() : body;
        String lakeTable = StrUtil.blankToDefault(str(param.get("lakeTable")), table);
        String note = str(param.get("note"));
        String ws = str(param.get("ws"));
        return CommonResult.data(reconRuleService.goldenAction(lakeTable, "rewrite_ck", note, ws));
    }

    @Operation(summary = "DS/Worker 推送 rewrite_ck 完成回调")
    @CommonLog("对账黄金回调")
    @PostMapping({
            "/lh/recon/golden/callback",
            "/lh/governance/reconcile/golden/callback",
            "/api/governance/reconcile/golden/callback"
    })
    public CommonResult<Map<String, Object>> goldenCallback(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = GovReconCallbackAuth.HEADER, required = false) String callbackToken) {
        assertCallbackAllowed(callbackToken);
        return CommonResult.data(reconRuleService.applyGoldenCallback(body));
    }

    /** 放行：共享 token 匹配，或已登录门户用户（联调兜底）。 */
    private void assertCallbackAllowed(String presentedToken) {
        String configured = lhProperties.getRecon() != null
                ? lhProperties.getRecon().getCallbackToken() : null;
        if (GovReconCallbackAuth.tokenMatches(configured, presentedToken)) {
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
            throw new CommonException("对账回调未配置 token，且当前未登录");
        }
        throw new CommonException("对账回调鉴权失败（检查 " + GovReconCallbackAuth.HEADER + "）");
    }

    private Map<String, Object> invokeGolden(String action, Map<String, Object> body) {
        Map<String, Object> param = body == null ? Map.of() : body;
        String lakeTable = StrUtil.blankToDefault(str(param.get("lakeTable")), str(param.get("table")));
        String note = str(param.get("note"));
        String ws = str(param.get("ws"));
        String act = StrUtil.isNotBlank(action) ? action : str(param.get("action"));
        return reconRuleService.goldenAction(lakeTable, act, note, ws);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }
}
