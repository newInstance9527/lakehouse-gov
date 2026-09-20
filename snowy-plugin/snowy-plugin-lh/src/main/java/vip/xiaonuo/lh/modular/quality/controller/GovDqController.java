package vip.xiaonuo.lh.modular.quality.controller;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.xiaonuo.common.annotation.CommonLog;
import vip.xiaonuo.common.pojo.CommonResult;
import vip.xiaonuo.lh.modular.quality.param.GovDqGateUpsertParam;
import vip.xiaonuo.lh.modular.quality.param.GovDqIdParam;
import vip.xiaonuo.lh.modular.quality.param.GovDqPageParam;
import vip.xiaonuo.lh.modular.quality.param.GovDqRunAddParam;
import vip.xiaonuo.lh.modular.quality.param.GovDqRuleUpsertParam;
import vip.xiaonuo.lh.modular.quality.result.GovDqRuleVo;
import vip.xiaonuo.lh.modular.quality.service.GovDqService;

import java.util.List;
import java.util.Map;

/**
 * 数据质量（对齐 doc/数据质量.md；独立前端无登录）
 *
 * @author lakehouse
 * @date 2026/9/19
 */
@Tag(name = "数据质量控制器")
@RestController
@Validated
public class GovDqController {

    @Resource
    private GovDqService govDqService;

    @Operation(summary = "KPI 概览")
    @GetMapping("/lh/quality/overview")
    public CommonResult<Map<String, Object>> overview(
            @RequestParam(required = false) String ws,
            @RequestParam(required = false) String range) {
        return CommonResult.data(govDqService.overview(ws, range));
    }

    @Operation(summary = "质量分趋势")
    @GetMapping("/lh/quality/trend")
    public CommonResult<List<Map<String, Object>>> trend(
            @RequestParam(required = false) String ws,
            @RequestParam(required = false) String range) {
        return CommonResult.data(govDqService.trend(ws, range));
    }

    @Operation(summary = "规则类型分布")
    @GetMapping("/lh/quality/type-dist")
    public CommonResult<List<Map<String, Object>>> typeDist(@RequestParam(required = false) String ws) {
        return CommonResult.data(govDqService.typeDist(ws));
    }

    @Operation(summary = "黄金表 Top")
    @GetMapping("/lh/quality/gold")
    public CommonResult<List<Map<String, Object>>> gold(
            @RequestParam(required = false) String ws,
            @RequestParam(required = false) Integer limit) {
        return CommonResult.data(govDqService.gold(ws, limit));
    }

    @Operation(summary = "规则分页")
    @GetMapping("/lh/quality/rules")
    public CommonResult<Page<GovDqRuleVo>> pageRules(GovDqPageParam param) {
        return CommonResult.data(govDqService.pageRules(param));
    }

    @Operation(summary = "新建/更新规则")
    @CommonLog("注册质量规则")
    @PostMapping("/lh/quality/rules")
    public CommonResult<GovDqRuleVo> upsertRule(@RequestBody @Valid GovDqRuleUpsertParam param) {
        return CommonResult.data(govDqService.upsertRule(param));
    }

    @Operation(summary = "删除规则")
    @CommonLog("删除质量规则")
    @PostMapping("/lh/quality/rules/delete")
    public CommonResult<String> deleteRule(@RequestBody @Valid GovDqIdParam param) {
        govDqService.deleteRule(param);
        return CommonResult.ok();
    }

    @Operation(summary = "规则运行历史")
    @GetMapping("/lh/quality/rules/runs")
    public CommonResult<Page<Map<String, Object>>> pageRuns(
            @RequestParam String ruleId,
            @RequestParam(required = false) String ws) {
        return CommonResult.data(govDqService.pageRuns(ruleId, ws));
    }

    @Operation(summary = "写入运行结果（作业回调）")
    @CommonLog("写入质量运行结果")
    @PostMapping("/lh/quality/rules/runs")
    public CommonResult<Map<String, Object>> addRun(@RequestBody @Valid GovDqRunAddParam param) {
        return CommonResult.data(govDqService.addRun(param));
    }

    @Operation(summary = "门禁列表")
    @GetMapping("/lh/quality/gates")
    public CommonResult<List<Map<String, Object>>> listGates(@RequestParam(required = false) String ws) {
        return CommonResult.data(govDqService.listGates(ws));
    }

    @Operation(summary = "配置门禁")
    @CommonLog("配置质量门禁")
    @PutMapping("/lh/quality/gates")
    public CommonResult<Map<String, Object>> upsertGate(@RequestBody GovDqGateUpsertParam param) {
        return CommonResult.data(govDqService.upsertGate(param));
    }

    @Operation(summary = "从失败规则开工单")
    @CommonLog("质量开工单")
    @PostMapping("/lh/quality/tickets")
    public CommonResult<Map<String, Object>> createTicket(@RequestBody Map<String, String> body) {
        return CommonResult.data(govDqService.createTicket(body.get("ruleId"), body.get("remark")));
    }

    @Operation(summary = "同步 OM 水位")
    @CommonLog("质量同步OM")
    @PostMapping("/lh/quality/sync-om")
    public CommonResult<Map<String, Object>> syncOm(@RequestParam(required = false) String ws) {
        return CommonResult.data(govDqService.syncOm(ws));
    }
}
