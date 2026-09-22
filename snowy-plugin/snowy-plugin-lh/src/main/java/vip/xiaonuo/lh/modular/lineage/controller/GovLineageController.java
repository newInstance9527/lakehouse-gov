package vip.xiaonuo.lh.modular.lineage.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.xiaonuo.common.annotation.CommonLog;
import vip.xiaonuo.common.pojo.CommonResult;
import vip.xiaonuo.lh.modular.lineage.param.GovLineageEdgeUpsertParam;
import vip.xiaonuo.lh.modular.lineage.param.GovLineageIdParam;
import vip.xiaonuo.lh.modular.lineage.param.GovLineagePageParam;
import vip.xiaonuo.lh.modular.lineage.result.GovLineageEdgeVo;
import vip.xiaonuo.lh.modular.lineage.service.GovLineageService;

import java.util.Map;

/**
 * 字段血缘（对齐 doc/字段血缘.md；独立前端无登录）
 *
 * @author lakehouse
 * @date 2026/9/19
 */
@Tag(name = "字段血缘控制器")
@RestController
@Validated
public class GovLineageController {

    @Resource
    private GovLineageService govLineageService;

    @Operation(summary = "血缘图（OM soft-fail + 门户字段边）")
    @GetMapping("/lh/lineage/graph")
    public CommonResult<Map<String, Object>> graph(
            @RequestParam(required = false) String node,
            @RequestParam(required = false) String focus,
            @RequestParam(required = false) String omFqn,
            @RequestParam(required = false) Integer upDepth,
            @RequestParam(required = false) Integer downDepth,
            @RequestParam(required = false) String ws) {
        return CommonResult.data(govLineageService.graph(node, focus, omFqn, upDepth, downDepth, ws));
    }

    @Operation(summary = "影响分析上下游清单")
    @GetMapping("/lh/lineage/impact")
    public CommonResult<Map<String, Object>> impact(
            @RequestParam(required = false) String node,
            @RequestParam(required = false) String focus,
            @RequestParam(required = false) String omFqn,
            @RequestParam(required = false) Integer upDepth,
            @RequestParam(required = false) Integer downDepth,
            @RequestParam(required = false) String ws) {
        return CommonResult.data(govLineageService.impact(node, focus, omFqn, upDepth, downDepth, ws));
    }

    @Operation(summary = "字段边分页")
    @GetMapping("/lh/lineage/fields")
    public CommonResult<Page<GovLineageEdgeVo>> pageFields(GovLineagePageParam param) {
        return CommonResult.data(govLineageService.pageFields(param));
    }

    @Operation(summary = "注册/更新字段边")
    @CommonLog("注册字段血缘边")
    @PostMapping("/lh/lineage/fields")
    public CommonResult<GovLineageEdgeVo> upsertField(@RequestBody @Valid GovLineageEdgeUpsertParam param) {
        return CommonResult.data(govLineageService.upsertField(param));
    }

    @Operation(summary = "删除字段边")
    @CommonLog("删除字段血缘边")
    @PostMapping("/lh/lineage/fields/delete")
    public CommonResult<String> deleteField(@RequestBody @Valid GovLineageIdParam param) {
        govLineageService.deleteField(param);
        return CommonResult.ok();
    }

    @Operation(summary = "同步字段边水位（ETL/Marquez）")
    @CommonLog("同步字段血缘")
    @PostMapping("/lh/lineage/fields/sync")
    public CommonResult<Map<String, Object>> syncFields(
            @RequestParam(required = false) String ws,
            @RequestParam(required = false) String etlJobId) {
        return CommonResult.data(govLineageService.syncFields(ws, etlJobId));
    }

    @Operation(summary = "同步水位状态")
    @GetMapping("/lh/lineage/fields/sync/status")
    public CommonResult<Map<String, Object>> syncStatus(@RequestParam(required = false) String ws) {
        return CommonResult.data(govLineageService.syncStatus(ws));
    }

    @Operation(summary = "生成变更评估（落库）")
    @CommonLog("血缘变更评估")
    @PostMapping("/lh/lineage/change-eval")
    public CommonResult<Map<String, Object>> changeEval(@RequestBody Map<String, String> body) {
        return CommonResult.data(govLineageService.changeEval(
                body.get("table"), body.get("field"), body.get("toType"), body.get("ws")));
    }

    @Operation(summary = "变更评估审批（approved/rejected）")
    @CommonLog("血缘变更评估审批")
    @PostMapping("/lh/lineage/change-eval/decide")
    public CommonResult<Map<String, Object>> decideChangeEval(@RequestBody Map<String, String> body) {
        return CommonResult.data(govLineageService.decideChangeEval(
                body.get("id"), body.get("decision"), body.get("ws")));
    }

    @Operation(summary = "登记阻断 DDL（落库，接入发布门禁）")
    @CommonLog("血缘阻断DDL")
    @PostMapping("/lh/lineage/block-ddl")
    public CommonResult<Map<String, Object>> blockDdl(@RequestBody Map<String, String> body) {
        return CommonResult.data(govLineageService.blockDdl(
                body.get("table"), body.get("field"), body.get("reason"), body.get("ws")));
    }

    @Operation(summary = "Marquez 命名空间（作业血缘探活）")
    @GetMapping("/lh/lineage/marquez/namespaces")
    public CommonResult<Map<String, Object>> marquezNamespaces() {
        return CommonResult.data(govLineageService.marquezNamespaces());
    }
}
