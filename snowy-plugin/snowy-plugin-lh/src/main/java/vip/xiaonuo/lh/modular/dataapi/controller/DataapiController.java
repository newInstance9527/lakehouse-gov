package vip.xiaonuo.lh.modular.dataapi.controller;

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
import vip.xiaonuo.lh.modular.dataapi.entity.DataapiApiBinding;
import vip.xiaonuo.lh.modular.dataapi.param.DataapiBindingParam;
import vip.xiaonuo.lh.modular.dataapi.param.DataapiGatewayProbeParam;
import vip.xiaonuo.lh.modular.dataapi.param.DataapiIdParam;
import vip.xiaonuo.lh.modular.dataapi.param.DataapiKeyRevealParam;
import vip.xiaonuo.lh.modular.dataapi.param.DataapiPageParam;
import vip.xiaonuo.lh.modular.dataapi.param.DataapiParseParam;
import vip.xiaonuo.lh.modular.dataapi.param.DataapiTagsParam;
import vip.xiaonuo.lh.modular.dataapi.param.DataapiTrialParam;
import vip.xiaonuo.lh.modular.dataapi.service.DataapiService;

import java.util.List;
import java.util.Map;

@Tag(name = "数据服务薄编排")
@RestController
@Validated
public class DataapiController {

    @Resource
    private DataapiService dataapiService;

    @Operation(summary = "KPI 概览")
    @GetMapping("/lh/dataapi/overview")
    public CommonResult<Map<String, Object>> overview(@RequestParam(required = false) String ws) {
        return CommonResult.data(dataapiService.overview(ws));
    }

    @Operation(summary = "绑定分页（Admin）")
    @GetMapping("/lh/dataapi/page")
    public CommonResult<Page<DataapiApiBinding>> page(DataapiPageParam param) {
        return CommonResult.data(dataapiService.page(param));
    }

    @Operation(summary = "门户 API 列表")
    @GetMapping("/lh/dataapi/apis")
    public CommonResult<List<Map<String, Object>>> apis(DataapiPageParam param) {
        return CommonResult.data(dataapiService.listApis(param));
    }

    @Operation(summary = "绑定详情")
    @GetMapping("/lh/dataapi/detail")
    public CommonResult<Map<String, Object>> detail(
            @RequestParam String id,
            @RequestParam(required = false, defaultValue = "true") boolean withSqlrest) {
        return CommonResult.data(dataapiService.detail(id, withSqlrest));
    }

    @Operation(summary = "新增绑定")
    @CommonLog("数据服务新增绑定")
    @PostMapping("/lh/dataapi/add")
    public CommonResult<DataapiApiBinding> add(@RequestBody @Valid DataapiBindingParam param) {
        return CommonResult.data(dataapiService.add(param));
    }

    @Operation(summary = "编辑绑定")
    @CommonLog("数据服务编辑绑定")
    @PostMapping("/lh/dataapi/edit")
    public CommonResult<DataapiApiBinding> edit(@RequestBody @Valid DataapiBindingParam param) {
        return CommonResult.data(dataapiService.edit(param));
    }

    @Operation(summary = "全量替换自定义标签（已发布亦可）")
    @CommonLog("数据服务更新 API 标签")
    @PostMapping("/lh/dataapi/updateTags")
    public CommonResult<Map<String, Object>> updateTags(@RequestBody @Valid DataapiTagsParam param) {
        return CommonResult.data(dataapiService.updateTags(param));
    }

    @Operation(summary = "向导构建：SQLREST create/update + 绑定")
    @CommonLog("数据服务构建 API")
    @PostMapping("/lh/dataapi/build")
    public CommonResult<Map<String, Object>> build(@RequestBody @Valid DataapiBindingParam param) {
        return CommonResult.data(dataapiService.build(param));
    }

    @Operation(summary = "试跑")
    @CommonLog("数据服务试跑")
    @PostMapping("/lh/dataapi/trial")
    public CommonResult<Map<String, Object>> trial(@RequestBody DataapiTrialParam param) {
        return CommonResult.data(dataapiService.trial(param));
    }

    @Operation(summary = "发布：SQLREST publish + deploy（边缘仅 Gateway，不写 APISIX）")
    @CommonLog("数据服务发布")
    @PostMapping("/lh/dataapi/publish")
    public CommonResult<Map<String, Object>> publish(@RequestBody @Valid DataapiIdParam param) {
        return CommonResult.data(dataapiService.publish(param));
    }

    @Operation(summary = "取消发布：下线回草稿，须重新申请发布；与永久下线(retire)不同")
    @CommonLog("数据服务取消发布")
    @PostMapping("/lh/dataapi/unpublish")
    public CommonResult<Map<String, Object>> unpublish(@RequestBody @Valid DataapiIdParam param) {
        return CommonResult.data(dataapiService.unpublish(param));
    }

    @Operation(summary = "下线（永久）")
    @CommonLog("数据服务下线")
    @PostMapping("/lh/dataapi/retire")
    public CommonResult<Map<String, Object>> retire(@RequestBody @Valid DataapiIdParam param) {
        return CommonResult.data(dataapiService.retire(param));
    }

    @Operation(summary = "发布版本列表（SQLREST version/list）")
    @GetMapping("/lh/dataapi/versions")
    public CommonResult<Map<String, Object>> versions(@RequestParam String id) {
        return CommonResult.data(dataapiService.listVersions(id));
    }

    @Operation(summary = "回退到历史版本并 deploy")
    @CommonLog("数据服务版本回退")
    @PostMapping("/lh/dataapi/rollback")
    public CommonResult<Map<String, Object>> rollback(@RequestBody @Valid DataapiIdParam param) {
        return CommonResult.data(dataapiService.rollback(param));
    }

    @Operation(summary = "删除绑定")
    @CommonLog("数据服务删除绑定")
    @PostMapping("/lh/dataapi/delete")
    public CommonResult<String> delete(@RequestBody @Valid DataapiIdParam param) {
        dataapiService.delete(param);
        return CommonResult.ok();
    }

    @Operation(summary = "已发布入口一览（Gateway；历史路径名 routes）")
    @GetMapping("/lh/dataapi/routes")
    public CommonResult<Map<String, Object>> routes() {
        return CommonResult.data(dataapiService.routes());
    }

    @Operation(summary = "已废弃：数据服务不做 APISIX，恒返回 skipped")
    @CommonLog("数据服务同步 APISIX（已废弃）")
    @PostMapping("/lh/dataapi/syncApisix")
    public CommonResult<Map<String, Object>> syncApisix(@RequestParam(required = false) String ws) {
        return CommonResult.data(dataapiService.syncApisix(ws));
    }

    @Operation(summary = "订阅 Key 元数据")
    @GetMapping("/lh/dataapi/keys")
    public CommonResult<List<Map<String, Object>>> keys(@RequestParam(required = false) String ws) {
        return CommonResult.data(dataapiService.keys(ws));
    }

    @Operation(summary = "订阅 Key 二次查看密文（Vault；申请人/管理员；写审计）")
    @CommonLog("数据服务查看订阅密钥")
    @PostMapping("/lh/dataapi/keys/reveal")
    public CommonResult<Map<String, Object>> revealKey(@RequestBody @Valid DataapiKeyRevealParam param) {
        return CommonResult.data(dataapiService.revealKey(param));
    }

    @Operation(summary = "外链 / 深链")
    @GetMapping("/lh/dataapi/embedUrl")
    public CommonResult<Map<String, Object>> embedUrl() {
        return CommonResult.data(dataapiService.embedUrl());
    }

    @Operation(summary = "SQLREST 工作台聚合（接口/调用/客户端概览）")
    @GetMapping("/lh/dataapi/workbench")
    public CommonResult<Map<String, Object>> workbench() {
        return CommonResult.data(dataapiService.workbench());
    }

    @Operation(summary = "从 SQLREST 同步接口目录")
    @CommonLog("数据服务同步 SQLREST 目录")
    @PostMapping("/lh/dataapi/syncFromSqlrest")
    public CommonResult<Map<String, Object>> syncFromSqlrest(@RequestParam(required = false) String ws) {
        return CommonResult.data(dataapiService.syncFromSqlrest(ws));
    }

    @Operation(summary = "登记已有 SQLREST 接口为绑定")
    @CommonLog("数据服务登记绑定")
    @PostMapping("/lh/dataapi/register")
    public CommonResult<Map<String, Object>> register(@RequestBody @Valid DataapiBindingParam param) {
        return CommonResult.data(dataapiService.register(param));
    }

    @Operation(summary = "入参解析（SQLREST assignment/parse）")
    @PostMapping("/lh/dataapi/parseParams")
    public CommonResult<Map<String, Object>> parseParams(@RequestBody DataapiParseParam param) {
        return CommonResult.data(dataapiService.parseParams(param));
    }

    @Operation(summary = "SQLREST 选项聚合（命名策略/类型格式/补全）")
    @GetMapping("/lh/dataapi/sqlrest/options")
    public CommonResult<Map<String, Object>> sqlrestOptions() {
        return CommonResult.data(dataapiService.sqlrestOptions());
    }

    @Operation(summary = "Gateway 联调探针")
    @PostMapping("/lh/dataapi/gatewayProbe")
    public CommonResult<Map<String, Object>> gatewayProbe(@RequestBody DataapiGatewayProbeParam param) {
        return CommonResult.data(dataapiService.gatewayProbe(param));
    }

    @Operation(summary = "调用大盘（SQLREST overview 聚合）")
    @GetMapping("/lh/dataapi/callStats")
    public CommonResult<Map<String, Object>> callStats(
            @RequestParam(required = false, defaultValue = "7") Integer days) {
        return CommonResult.data(dataapiService.callStats(days));
    }

    @Operation(summary = "OpenAPI 3.0 导出（published 绑定；可选单条 id）")
    @GetMapping({"/lh/dataapi/openapi.json", "/lh/dataapi/openapi"})
    public CommonResult<Map<String, Object>> openapi(
            @RequestParam(required = false) String ws,
            @RequestParam(required = false) String id) {
        return CommonResult.data(dataapiService.openapi(ws, id));
    }
}
