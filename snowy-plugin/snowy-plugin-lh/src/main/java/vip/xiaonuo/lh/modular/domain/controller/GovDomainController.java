package vip.xiaonuo.lh.modular.domain.controller;

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
import vip.xiaonuo.lh.modular.domain.param.GovDomainUpsertParam;
import vip.xiaonuo.lh.modular.domain.service.GovDomainService;

import java.util.List;
import java.util.Map;

/**
 * 业务数据域 SoT（/lh/domain/*）
 */
@Tag(name = "数据域")
@RestController
@Validated
public class GovDomainController {

    @Resource
    private GovDomainService govDomainService;

    @Operation(summary = "域列表")
    @GetMapping("/lh/domain/list")
    public CommonResult<List<Map<String, Object>>> list(
            @RequestParam(required = false) String ws,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q) {
        return CommonResult.data(govDomainService.list(ws, status, q));
    }

    @Operation(summary = "启用域选项（value/label）")
    @GetMapping("/lh/domain/options")
    public CommonResult<List<Map<String, Object>>> options() {
        return CommonResult.data(govDomainService.options());
    }

    @Operation(summary = "域详情")
    @GetMapping("/lh/domain/{code}")
    public CommonResult<Map<String, Object>> detail(@PathVariable("code") String code) {
        return CommonResult.data(govDomainService.detail(code));
    }

    @Operation(summary = "域引用统计")
    @GetMapping("/lh/domain/{code}/usage")
    public CommonResult<Map<String, Object>> usage(@PathVariable("code") String code) {
        return CommonResult.data(govDomainService.usage(code));
    }

    @Operation(summary = "新建域")
    @CommonLog("新建数据域")
    @PostMapping("/lh/domain")
    public CommonResult<Map<String, Object>> create(@RequestBody @Valid GovDomainUpsertParam param) {
        return CommonResult.data(govDomainService.create(param));
    }

    @Operation(summary = "更新域")
    @CommonLog("更新数据域")
    @PutMapping("/lh/domain/{code}")
    public CommonResult<Map<String, Object>> update(
            @PathVariable("code") String code,
            @RequestBody GovDomainUpsertParam param) {
        return CommonResult.data(govDomainService.update(code, param));
    }

    @Operation(summary = "停用域")
    @CommonLog("停用数据域")
    @PostMapping("/lh/domain/{code}/disable")
    public CommonResult<Map<String, Object>> disable(@PathVariable("code") String code) {
        return CommonResult.data(govDomainService.disable(code));
    }

    @Operation(summary = "启用域")
    @CommonLog("启用数据域")
    @PostMapping("/lh/domain/{code}/enable")
    public CommonResult<Map<String, Object>> enable(@PathVariable("code") String code) {
        return CommonResult.data(govDomainService.enable(code));
    }

    @Operation(summary = "删除域（软删）")
    @CommonLog("删除数据域")
    @PostMapping("/lh/domain/{code}/delete")
    public CommonResult<String> delete(@PathVariable("code") String code) {
        govDomainService.softDelete(code);
        return CommonResult.ok();
    }
}
