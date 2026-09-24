package vip.xiaonuo.lh.modular.contract.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
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
import vip.xiaonuo.lh.modular.contract.service.ContractService;

import java.util.List;
import java.util.Map;

@Tag(name = "数据契约")
@RestController
@Validated
public class ContractController {

    @Resource
    private ContractService contractService;

    @Operation(summary = "契约 KPI")
    @GetMapping("/lh/contract/overview")
    public CommonResult<Map<String, Object>> overview(@RequestParam(required = false) String ws) {
        return CommonResult.data(contractService.overview(ws));
    }

    @Operation(summary = "Schema 列表")
    @GetMapping("/lh/contract/schemas")
    public CommonResult<Map<String, Object>> schemas(
            @RequestParam(required = false) String ws,
            @RequestParam(required = false) String q) {
        return CommonResult.data(contractService.listSchemas(ws, q));
    }

    @Operation(summary = "注册 Schema")
    @CommonLog("契约注册 Schema")
    @PostMapping("/lh/contract/schemas")
    public CommonResult<Map<String, Object>> register(@RequestBody Map<String, Object> body) {
        return CommonResult.data(contractService.registerSchema(body));
    }

    @Operation(summary = "Schema 版本历史")
    @GetMapping("/lh/contract/schemas/{name}/versions")
    public CommonResult<List<Map<String, Object>>> versions(
            @PathVariable String name,
            @RequestParam(required = false) String ws) {
        return CommonResult.data(contractService.schemaVersions(name, ws));
    }

    @Operation(summary = "变更单列表")
    @GetMapping("/lh/contract/changes")
    public CommonResult<Map<String, Object>> changes(
            @RequestParam(required = false) String ws,
            @RequestParam(required = false) String status) {
        return CommonResult.data(contractService.listChanges(ws, status));
    }

    @Operation(summary = "创建变更单")
    @CommonLog("契约创建变更单")
    @PostMapping("/lh/contract/changes")
    public CommonResult<Map<String, Object>> createChange(@RequestBody Map<String, Object> body) {
        return CommonResult.data(contractService.createChange(body));
    }

    @Operation(summary = "变更单状态流转")
    @CommonLog("契约变更单动作")
    @PostMapping("/lh/contract/changes/{id}/action")
    public CommonResult<Map<String, Object>> changeAction(
            @PathVariable String id,
            @RequestParam String action,
            @RequestBody(required = false) Map<String, Object> body) {
        return CommonResult.data(contractService.changeAction(id, action, body));
    }

    @Operation(summary = "兼容性检查 stub")
    @PostMapping("/lh/contract/check")
    public CommonResult<Map<String, Object>> check(@RequestBody Map<String, Object> body) {
        return CommonResult.data(contractService.check(body));
    }

    @Operation(summary = "CDC 语义配置")
    @GetMapping("/lh/contract/cdc-config/{topic}")
    public CommonResult<Map<String, Object>> getCdc(
            @PathVariable String topic,
            @RequestParam(required = false) String ws) {
        return CommonResult.data(contractService.getCdcConfig(topic, ws));
    }

    @Operation(summary = "保存 CDC 语义配置")
    @CommonLog("契约 CDC 配置")
    @PutMapping("/lh/contract/cdc-config/{topic}")
    public CommonResult<Map<String, Object>> putCdc(
            @PathVariable String topic,
            @RequestParam(required = false) String ws,
            @RequestBody Map<String, Object> body) {
        return CommonResult.data(contractService.putCdcConfig(topic, ws, body));
    }
}
