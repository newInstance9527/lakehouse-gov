package vip.xiaonuo.lh.modular.compute.controller;

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
import vip.xiaonuo.common.annotation.CommonLog;
import vip.xiaonuo.common.pojo.CommonResult;
import vip.xiaonuo.lh.modular.compute.param.CpReleaseCreateParam;
import vip.xiaonuo.lh.modular.compute.param.CpScriptCreateParam;
import vip.xiaonuo.lh.modular.compute.param.CpScriptRunParam;
import vip.xiaonuo.lh.modular.compute.param.CpScriptSaveParam;
import vip.xiaonuo.lh.modular.compute.service.CpDevelopService;

import java.util.List;
import java.util.Map;

/**
 * 数据开发 / SQL 工作台与发布门禁。
 */
@Tag(name = "数据开发")
@RestController
@Validated
public class CpDevelopController {

    @Resource
    private CpDevelopService cpDevelopService;

    @Operation(summary = "脚本树")
    @GetMapping({"/lh/compute/scripts/tree", "/api/compute/scripts/tree"})
    public CommonResult<Map<String, Object>> tree(@RequestParam(required = false) String ws) {
        return CommonResult.data(cpDevelopService.tree(ws));
    }

    @Operation(summary = "读取脚本正文")
    @GetMapping({"/lh/compute/scripts/content", "/api/compute/scripts/content"})
    public CommonResult<Map<String, Object>> content(@RequestParam String id) {
        return CommonResult.data(cpDevelopService.content(id));
    }

    @Operation(summary = "新建脚本")
    @CommonLog("新建开发脚本")
    @PostMapping({"/lh/compute/scripts", "/api/compute/scripts"})
    public CommonResult<Map<String, Object>> create(@RequestBody CpScriptCreateParam param) {
        return CommonResult.data(cpDevelopService.create(param));
    }

    @Operation(summary = "保存脚本到 Git")
    @CommonLog("保存开发脚本")
    @PostMapping({"/lh/compute/scripts/commit", "/api/compute/scripts/commit"})
    public CommonResult<Map<String, Object>> commit(@RequestBody CpScriptSaveParam param) {
        return CommonResult.data(cpDevelopService.commit(param));
    }

    @Operation(summary = "TEST/PRE 试跑")
    @CommonLog("开发脚本试跑")
    @PostMapping({"/lh/compute/scripts/run-stg", "/api/compute/scripts/run-stg"})
    public CommonResult<Map<String, Object>> runStg(@RequestBody CpScriptRunParam param) {
        return CommonResult.data(cpDevelopService.runStg(param));
    }

    @Operation(summary = "试跑记录")
    @GetMapping({"/lh/compute/scripts/runs", "/api/compute/scripts/runs"})
    public CommonResult<List<Map<String, Object>>> runs(@RequestParam String scriptId) {
        return CommonResult.data(cpDevelopService.runs(scriptId));
    }

    @Operation(summary = "试跑结果（运行中会向调度刷新）")
    @GetMapping({"/lh/compute/scripts/runs/{runId}", "/api/compute/scripts/runs/{runId}"})
    public CommonResult<Map<String, Object>> runDetail(@PathVariable String runId) {
        return CommonResult.data(cpDevelopService.runDetail(runId));
    }

    @Operation(summary = "开发页指标")
    @GetMapping("/lh/compute/scripts/kpis")
    public CommonResult<List<Map<String, Object>>> kpis(@RequestParam(required = false) String ws) {
        return CommonResult.data(cpDevelopService.kpis(ws));
    }

    @Operation(summary = "UDF 登记")
    @GetMapping("/lh/compute/udfs")
    public CommonResult<List<Map<String, Object>>> udfs(@RequestParam(required = false) String engine) {
        return CommonResult.data(cpDevelopService.udfs(engine));
    }

    @Operation(summary = "发布单列表")
    @GetMapping({"/lh/compute/releases", "/api/compute/releases"})
    public CommonResult<List<Map<String, Object>>> releases(@RequestParam(required = false) String ws) {
        return CommonResult.data(cpDevelopService.releases(ws));
    }

    @Operation(summary = "提交上版前门禁预检（不生成发布单）")
    @GetMapping({"/lh/compute/scripts/release-precheck", "/api/compute/scripts/release-precheck"})
    public CommonResult<Map<String, Object>> releasePrecheck(
            @RequestParam String scriptId,
            @RequestParam(required = false) String engine,
            @RequestParam(required = false) String env) {
        return CommonResult.data(cpDevelopService.releasePrecheck(scriptId, engine, env));
    }

    @Operation(summary = "提交上版")
    @CommonLog("提交脚本发布单")
    @PostMapping({"/lh/compute/releases", "/api/compute/releases"})
    public CommonResult<Map<String, Object>> createRelease(@RequestBody CpReleaseCreateParam param) {
        return CommonResult.data(cpDevelopService.createRelease(param));
    }

    @Operation(summary = "发布门禁")
    @GetMapping({"/lh/compute/releases/{id}/gates", "/api/compute/releases/{id}/gates"})
    public CommonResult<Map<String, Object>> gates(@PathVariable String id) {
        return CommonResult.data(cpDevelopService.gates(id));
    }

    @Operation(summary = "门禁通过后发布")
    @CommonLog("发布脚本")
    @PostMapping({"/lh/compute/releases/{id}/publish", "/api/compute/releases/{id}/publish"})
    public CommonResult<Map<String, Object>> publish(@PathVariable String id) {
        return CommonResult.data(cpDevelopService.publish(id));
    }

    @Operation(summary = "回滚到上一 Git tag")
    @CommonLog("回滚脚本发布")
    @PostMapping({"/lh/compute/releases/{id}/rollback", "/api/compute/releases/{id}/rollback"})
    public CommonResult<Map<String, Object>> rollback(@PathVariable String id) {
        return CommonResult.data(cpDevelopService.rollback(id));
    }
}
