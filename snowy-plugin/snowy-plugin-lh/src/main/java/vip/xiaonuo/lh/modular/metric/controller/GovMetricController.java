package vip.xiaonuo.lh.modular.metric.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.xiaonuo.common.annotation.CommonLog;
import vip.xiaonuo.common.pojo.CommonResult;
import cn.hutool.core.util.StrUtil;
import vip.xiaonuo.lh.modular.metric.param.GovMetricCompileParam;
import vip.xiaonuo.lh.modular.metric.param.GovMetricMaterializeParam;
import vip.xiaonuo.lh.modular.metric.param.GovMetricPageParam;
import vip.xiaonuo.lh.modular.metric.param.GovMetricQueryParam;
import vip.xiaonuo.lh.modular.metric.param.GovMetricTransitionParam;
import vip.xiaonuo.lh.modular.metric.param.GovMetricTrialParam;
import vip.xiaonuo.lh.modular.metric.param.GovMetricUpsertParam;
import vip.xiaonuo.lh.modular.metric.result.GovMetricVo;
import vip.xiaonuo.lh.modular.metric.service.GovMetricService;
import vip.xiaonuo.lh.modular.recon.support.ReconPartitionService;

import java.util.List;
import java.util.Map;

/**
 * 指标中心（对齐 doc/指标中心.md P0）
 */
@Tag(name = "指标中心控制器")
@RestController
@Validated
public class GovMetricController {

    @Resource
    private GovMetricService govMetricService;
    @Resource
    private ReconPartitionService reconPartitionService;

    @Operation(summary = "KPI 概览")
    @GetMapping("/lh/metric/overview")
    public CommonResult<Map<String, Object>> overview(@RequestParam(required = false) String ws) {
        return CommonResult.data(govMetricService.overview(ws));
    }

    @Operation(summary = "核心看板（对账门禁）")
    @GetMapping("/lh/metric/board")
    public CommonResult<Map<String, Object>> board(@RequestParam(required = false) String ws) {
        return CommonResult.data(govMetricService.board(ws));
    }

    @Operation(summary = "指标分页列表")
    @GetMapping("/lh/metric/list")
    public CommonResult<Page<GovMetricVo>> list(GovMetricPageParam param) {
        return CommonResult.data(govMetricService.page(param));
    }

    @Operation(summary = "指标详情")
    @GetMapping("/lh/metric/{code}")
    public CommonResult<GovMetricVo> detail(
            @PathVariable("code") String code,
            @RequestParam(required = false) String ws) {
        return CommonResult.data(govMetricService.detail(code, ws));
    }

    @Operation(summary = "新建指标草稿")
    @CommonLog("新建指标")
    @PostMapping("/lh/metric")
    public CommonResult<GovMetricVo> create(@RequestBody @Valid GovMetricUpsertParam param) {
        return CommonResult.data(govMetricService.create(param));
    }

    @Operation(summary = "更新指标草稿")
    @CommonLog("更新指标")
    @PutMapping("/lh/metric/{code}")
    public CommonResult<GovMetricVo> update(
            @PathVariable("code") String code,
            @RequestBody @Valid GovMetricUpsertParam param) {
        param.setMetricCode(code);
        return CommonResult.data(govMetricService.update(param));
    }

    @Operation(summary = "状态流转")
    @CommonLog("指标状态流转")
    @PostMapping("/lh/metric/{code}/transition")
    public CommonResult<GovMetricVo> transition(
            @PathVariable("code") String code,
            @RequestBody @Valid GovMetricTransitionParam param) {
        param.setMetricCode(code);
        return CommonResult.data(govMetricService.transition(param));
    }

    @Operation(summary = "编译 SQL（试跑/缓存）")
    @PostMapping("/lh/metric/compile")
    public CommonResult<Map<String, Object>> compile(@RequestBody GovMetricCompileParam param) {
        return CommonResult.data(govMetricService.compile(param));
    }

    @Operation(summary = "统一口径查询（Trino 执行）")
    @PostMapping("/lh/metric/query")
    public CommonResult<Map<String, Object>> query(@RequestBody @Valid GovMetricQueryParam param) {
        return CommonResult.data(govMetricService.query(param));
    }

    @Operation(summary = "指标试跑（草稿可用，截断更严）")
    @PostMapping("/lh/metric/{code}/trial")
    public CommonResult<Map<String, Object>> trial(
            @PathVariable("code") String code,
            @RequestBody(required = false) GovMetricTrialParam param) {
        return CommonResult.data(govMetricService.trial(code, param));
    }

    @Operation(summary = "指标血缘上下游")
    @GetMapping("/lh/metric/{code}/lineage")
    public CommonResult<Map<String, Object>> lineage(
            @PathVariable("code") String code,
            @RequestParam(required = false) String ws) {
        return CommonResult.data(govMetricService.lineage(code, ws));
    }

    @Operation(summary = "指标日波动摘要")
    @GetMapping("/lh/metric/{code}/anomaly")
    public CommonResult<Map<String, Object>> anomaly(
            @PathVariable("code") String code,
            @RequestParam(required = false) String ws,
            @RequestParam(required = false) Integer days) {
        return CommonResult.data(govMetricService.anomaly(code, ws, days));
    }

    @Operation(summary = "手动触发指标日波动采样")
    @CommonLog("指标波动采样")
    @PostMapping("/lh/metric/anomaly/rerun")
    public CommonResult<Map<String, Object>> sampleRerun(@RequestParam(required = false) String ws) {
        return CommonResult.data(govMetricService.sampleRerun(ws));
    }

    @Operation(summary = "物化登记列表")
    @GetMapping("/lh/metric/{code}/materialize")
    public CommonResult<List<Map<String, Object>>> listMaterialize(
            @PathVariable("code") String code,
            @RequestParam(required = false) String ws) {
        return CommonResult.data(govMetricService.listMaterialize(code, ws));
    }

    @Operation(summary = "登记/触发物化作业模板")
    @CommonLog("指标物化")
    @PostMapping("/lh/metric/{code}/materialize")
    public CommonResult<Map<String, Object>> materialize(
            @PathVariable("code") String code,
            @RequestBody(required = false) GovMetricMaterializeParam param) {
        return CommonResult.data(govMetricService.materialize(code, param));
    }

    @Operation(summary = "分区对账流水（看板/可靠性）")
    @GetMapping("/lh/recon/partition")
    public CommonResult<Map<String, Object>> reconPartitionList(
            @RequestParam(required = false) String metricCode,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit) {
        return CommonResult.data(reconPartitionService.listRecent(
                metricCode, status, limit == null ? 50 : limit));
    }

    @Operation(summary = "登记分区对账结果并回写 recon_ok")
    @CommonLog("分区对账登记")
    @PostMapping("/lh/recon/partition/run")
    public CommonResult<Map<String, Object>> reconPartitionRun(@RequestBody Map<String, Object> body) {
        Object code = body != null ? body.get("metricCode") : null;
        if (code != null && StrUtil.isNotBlank(String.valueOf(code))) {
            return CommonResult.data(reconPartitionService.runForMetric(String.valueOf(code), body));
        }
        return CommonResult.data(reconPartitionService.record(body == null ? Map.of() : body));
    }
}
