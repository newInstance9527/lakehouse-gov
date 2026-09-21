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
import vip.xiaonuo.lh.modular.dataapi.param.DataapiPageParam;
import vip.xiaonuo.lh.modular.dataapi.param.DataapiParseParam;
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

    @Operation(summary = "发布：SQLREST 上线；APISIX 仅 edgeMode=apisix|both")
    @CommonLog("数据服务发布")
    @PostMapping("/lh/dataapi/publish")
    public CommonResult<Map<String, Object>> publish(@RequestBody @Valid DataapiIdParam param) {
        return CommonResult.data(dataapiService.publish(param));
    }

    @Operation(summary = "下线")
    @CommonLog("数据服务下线")
    @PostMapping("/lh/dataapi/retire")
    public CommonResult<Map<String, Object>> retire(@RequestBody @Valid DataapiIdParam param) {
        return CommonResult.data(dataapiService.retire(param));
    }

    @Operation(summary = "删除绑定")
    @CommonLog("数据服务删除绑定")
    @PostMapping("/lh/dataapi/delete")
    public CommonResult<String> delete(@RequestBody @Valid DataapiIdParam param) {
        dataapiService.delete(param);
        return CommonResult.ok();
    }

    @Operation(summary = "APISIX 路由投影")
    @GetMapping("/lh/dataapi/routes")
    public CommonResult<Map<String, Object>> routes() {
        return CommonResult.data(dataapiService.routes());
    }

    @Operation(summary = "与 APISIX 同步")
    @CommonLog("数据服务同步 APISIX")
    @PostMapping("/lh/dataapi/syncApisix")
    public CommonResult<Map<String, Object>> syncApisix(@RequestParam(required = false) String ws) {
        return CommonResult.data(dataapiService.syncApisix(ws));
    }

    @Operation(summary = "订阅 Key 元数据")
    @GetMapping("/lh/dataapi/keys")
    public CommonResult<List<Map<String, Object>>> keys(@RequestParam(required = false) String ws) {
        return CommonResult.data(dataapiService.keys(ws));
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
}
