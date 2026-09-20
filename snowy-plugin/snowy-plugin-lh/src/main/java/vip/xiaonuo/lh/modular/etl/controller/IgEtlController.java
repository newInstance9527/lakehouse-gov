package vip.xiaonuo.lh.modular.etl.controller;

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
import vip.xiaonuo.lh.modular.etl.param.IgEtlBackfillParam;
import vip.xiaonuo.lh.modular.etl.param.IgEtlDagAddParam;
import vip.xiaonuo.lh.modular.etl.param.IgEtlDagEditParam;
import vip.xiaonuo.lh.modular.etl.param.IgEtlDeployParam;
import vip.xiaonuo.lh.modular.etl.param.IgEtlEngineResolveParam;
import vip.xiaonuo.lh.modular.etl.param.IgEtlGraphSaveParam;
import vip.xiaonuo.lh.modular.etl.param.IgEtlIdParam;
import vip.xiaonuo.lh.modular.etl.param.IgEtlNodeConfigParam;
import vip.xiaonuo.lh.modular.etl.param.IgEtlPageParam;
import vip.xiaonuo.lh.modular.etl.param.IgEtlTrialParam;
import vip.xiaonuo.lh.modular.etl.service.IgEtlService;

import java.util.Map;

/**
 * ETL 编排（对齐 doc/ETL编排.md；独立前端可无登录）
 *
 * @author lakehouse
 * @date 2026/9/19
 */
@Tag(name = "ETL编排控制器")
@RestController
@Validated
public class IgEtlController {

    @Resource
    private IgEtlService igEtlService;

    @Operation(summary = "DAG 分页")
    @GetMapping("/lh/etl/dags")
    public CommonResult<Page<Map<String, Object>>> pageDags(IgEtlPageParam param) {
        return CommonResult.data(igEtlService.pageDags(param));
    }

    @Operation(summary = "新建 DAG")
    @CommonLog("新建ETL DAG")
    @PostMapping("/lh/etl/dags")
    public CommonResult<Map<String, Object>> addDag(@RequestBody @Valid IgEtlDagAddParam param) {
        return CommonResult.data(igEtlService.addDag(param));
    }

    @Operation(summary = "DAG 详情")
    @GetMapping("/lh/etl/dags/detail")
    public CommonResult<Map<String, Object>> detail(@RequestParam String id) {
        return CommonResult.data(igEtlService.detail(id));
    }

    @Operation(summary = "编辑 DAG 头")
    @CommonLog("编辑ETL DAG")
    @PostMapping("/lh/etl/dags/edit")
    public CommonResult<Map<String, Object>> editDag(@RequestBody @Valid IgEtlDagEditParam param) {
        return CommonResult.data(igEtlService.editDag(param));
    }

    @Operation(summary = "读取 DAG 图")
    @GetMapping("/lh/etl/dags/graph")
    public CommonResult<Map<String, Object>> graph(@RequestParam String id) {
        return CommonResult.data(igEtlService.graph(id));
    }

    @Operation(summary = "保存 DAG 图")
    @CommonLog("保存ETL图")
    @PostMapping("/lh/etl/dags/graph")
    public CommonResult<Map<String, Object>> saveGraph(@RequestBody @Valid IgEtlGraphSaveParam param) {
        return CommonResult.data(igEtlService.saveGraph(param));
    }

    @Operation(summary = "更新节点 conf")
    @CommonLog("更新ETL节点配置")
    @PutMapping("/lh/etl/dags/nodes/config")
    public CommonResult<Map<String, Object>> updateNodeConfig(@RequestBody @Valid IgEtlNodeConfigParam param) {
        return CommonResult.data(igEtlService.updateNodeConfig(param));
    }

    @Operation(summary = "校验 DAG")
    @PostMapping("/lh/etl/dags/validate")
    public CommonResult<Map<String, Object>> validate(@RequestBody @Valid IgEtlIdParam param) {
        return CommonResult.data(igEtlService.validate(param));
    }

    @Operation(summary = "试跑")
    @CommonLog("ETL试跑")
    @PostMapping("/lh/etl/dags/trial")
    public CommonResult<Map<String, Object>> trial(@RequestBody @Valid IgEtlTrialParam param) {
        return CommonResult.data(igEtlService.trial(param));
    }

    @Operation(summary = "发布到 DS")
    @CommonLog("ETL发布")
    @PostMapping("/lh/etl/dags/deploy")
    public CommonResult<Map<String, Object>> deploy(@RequestBody @Valid IgEtlDeployParam param) {
        return CommonResult.data(igEtlService.deploy(param));
    }

    @Operation(summary = "补数（按水位 mark_key/mark_value）")
    @CommonLog("ETL补数")
    @PostMapping("/lh/etl/dags/backfill")
    public CommonResult<Map<String, Object>> backfill(@RequestBody @Valid IgEtlBackfillParam param) {
        return CommonResult.data(igEtlService.backfill(param));
    }

    @Operation(summary = "运行列表")
    @GetMapping("/lh/etl/dags/runs")
    public CommonResult<Page<Map<String, Object>>> pageRuns(
            @RequestParam(required = false) String dagId,
            @RequestParam(required = false) String ws) {
        return CommonResult.data(igEtlService.pageRuns(dagId, ws));
    }

    @Operation(summary = "运行详情")
    @GetMapping("/lh/etl/dags/runs/detail")
    public CommonResult<Map<String, Object>> runDetail(@RequestParam String runId) {
        return CommonResult.data(igEtlService.runDetail(runId));
    }

    @Operation(summary = "运行态回调（DS/Worker 回写）")
    @CommonLog("ETL运行回调")
    @PostMapping("/lh/etl/dags/runs/callback")
    public CommonResult<Map<String, Object>> runCallback(@RequestBody Map<String, Object> body) {
        return CommonResult.data(igEtlService.applyRunCallback(body));
    }

    @Operation(summary = "试算引擎")
    @PostMapping("/lh/etl/engine/resolve")
    public CommonResult<Map<String, Object>> resolveEngine(@RequestBody IgEtlEngineResolveParam param) {
        return CommonResult.data(igEtlService.resolveEngine(param));
    }

    @Operation(summary = "节点类型元数据")
    @GetMapping("/lh/etl/meta/node-types")
    public CommonResult<Map<String, Object>> nodeTypes() {
        return CommonResult.data(igEtlService.nodeTypes());
    }
}
