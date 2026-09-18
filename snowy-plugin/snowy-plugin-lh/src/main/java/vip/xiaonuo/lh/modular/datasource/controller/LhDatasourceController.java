/*
 * Copyright [2022] [https://www.xiaonuo.vip]
 *
 * Snowy??APACHE LICENSE 2.0??????????????????????
 *
 * 1.?????????????LICENSE???
 * 2.????????Snowy??????????
 * 3.?????????????????????????????????????????
 * 4.?????????????? https://www.xiaonuo.vip
 * 5.????????????????????????xiaonuobase@qq.com?????
 * 6.?????????????????????????Snowy??????????????????? https://www.xiaonuo.vip
 */
package vip.xiaonuo.lh.modular.datasource.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import vip.xiaonuo.common.annotation.CommonLog;
import vip.xiaonuo.common.pojo.CommonResult;
import vip.xiaonuo.lh.modular.datasource.entity.LhConsumerBinding;
import vip.xiaonuo.lh.modular.datasource.param.*;
import vip.xiaonuo.lh.modular.datasource.result.LhDatasourceVo;
import vip.xiaonuo.lh.modular.datasource.result.LhDsTableVo;
import vip.xiaonuo.lh.modular.datasource.service.LhDatasourceService;

import java.util.List;
import java.util.Map;

/**
 * ???????????/??????????????????? @SaIgnore?
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Tag(name = "????????")
@SaIgnore
@RestController
@Validated
public class LhDatasourceController {

    @Resource
    private LhDatasourceService datasourceService;

    @Operation(summary = "?????")
    @GetMapping("/lh/datasource/page")
    public CommonResult<Page<LhDatasourceVo>> page(LhDatasourcePageParam param) {
        return CommonResult.data(datasourceService.page(param));
    }

    @Operation(summary = "?????")
    @CommonLog("?????")
    @PostMapping("/lh/datasource/add")
    public CommonResult<LhDatasourceVo> add(@RequestBody @Valid LhDatasourceAddParam param) {
        return CommonResult.data(datasourceService.add(param));
    }

    @Operation(summary = "?????")
    @CommonLog("?????")
    @PostMapping("/lh/datasource/edit")
    public CommonResult<LhDatasourceVo> edit(@RequestBody @Valid LhDatasourceEditParam param) {
        return CommonResult.data(datasourceService.edit(param));
    }

    @Operation(summary = "?????")
    @CommonLog("?????")
    @PostMapping("/lh/datasource/delete")
    public CommonResult<String> delete(@RequestBody @Valid @NotEmpty List<LhDatasourceIdParam> ids) {
        datasourceService.delete(ids);
        return CommonResult.ok();
    }

    @Operation(summary = "?????")
    @GetMapping("/lh/datasource/detail")
    public CommonResult<LhDatasourceVo> detail(@Valid LhDatasourceIdParam param) {
        return CommonResult.data(datasourceService.detail(param));
    }

    @Operation(summary = "?????")
    @PostMapping("/lh/datasource/test")
    public CommonResult<Map<String, Object>> test(@RequestBody LhDatasourceTestParam param) {
        return CommonResult.data(datasourceService.test(param));
    }

    @Operation(summary = "Schema??")
    @GetMapping("/lh/datasource/previewSchema")
    public CommonResult<Map<String, Object>> previewSchema(@Valid LhDatasourceIdParam param) {
        return CommonResult.data(datasourceService.previewSchema(param));
    }

    @Operation(summary = "????")
    @CommonLog("???????")
    @PostMapping("/lh/datasource/purposes")
    public CommonResult<String> purposes(@RequestBody @Valid LhDatasourcePurposesParam param) {
        datasourceService.updatePurposes(param);
        return CommonResult.ok();
    }

    @Operation(summary = "???????")
    @GetMapping("/lh/datasource/bindings")
    public CommonResult<List<LhConsumerBinding>> bindings(@Valid LhDatasourceIdParam param) {
        return CommonResult.data(datasourceService.bindings(param));
    }

    @Operation(summary = "????")
    @CommonLog("???????")
    @PostMapping("/lh/datasource/rotateCred")
    public CommonResult<String> rotateCred(@RequestBody @Valid LhDatasourceIdParam param) {
        datasourceService.rotateCred(param);
        return CommonResult.ok();
    }

    @Operation(summary = "DAG???")
    @GetMapping("/lh/datasource/listForDag")
    public CommonResult<List<LhDatasourceVo>> listForDag() {
        return CommonResult.data(datasourceService.listForDag());
    }

    @Operation(summary = "Superset??(?Trino)")
    @GetMapping("/lh/datasource/supersetProjection")
    public CommonResult<List<LhDatasourceVo>> supersetProjection() {
        return CommonResult.data(datasourceService.supersetProjection());
    }

    @Operation(summary = "????")
    @CommonLog("???????")
    @PostMapping("/lh/datasource/batchImport")
    public CommonResult<String> batchImport(@RequestBody @Valid LhDatasourceBatchImportParam param) {
        datasourceService.batchImport(param);
        return CommonResult.ok();
    }

    @Operation(summary = "????")
    @CommonLog("?????")
    @PostMapping("/lh/datasource/toggleStatus")
    public CommonResult<Map<String, Object>> toggleStatus(@RequestBody @Valid LhDatasourceToggleParam param) {
        return CommonResult.data(datasourceService.toggleStatus(param));
    }

    @Operation(summary = "KPI??")
    @GetMapping("/lh/datasource/kpi")
    public CommonResult<Map<String, Object>> kpi() {
        return CommonResult.data(datasourceService.kpi());
    }

    @Operation(summary = "????")
    @GetMapping("/lh/datasource/typeOptions")
    public CommonResult<List<Map<String, Object>>> typeOptions() {
        return CommonResult.data(datasourceService.typeOptions());
    }

    @Operation(summary = "????Schema")
    @GetMapping("/lh/datasource/formSchema")
    public CommonResult<Map<String, Object>> formSchema(
            @RequestParam(value = "type", required = false) String type) {
        return CommonResult.data(datasourceService.formSchema(type));
    }

    @Operation(summary = "?????")
    @GetMapping("/lh/datasource/table/page")
    public CommonResult<Page<LhDsTableVo>> tablePage(@Valid LhDsTablePageParam param) {
        return CommonResult.data(datasourceService.tablePage(param));
    }

    @Operation(summary = "?????")
    @CommonLog("????????")
    @PostMapping("/lh/datasource/table/add")
    public CommonResult<LhDsTableVo> tableAdd(@RequestBody @Valid LhDsTableAddParam param) {
        return CommonResult.data(datasourceService.tableAdd(param));
    }

    @Operation(summary = "?????")
    @CommonLog("????????")
    @PostMapping("/lh/datasource/table/edit")
    public CommonResult<LhDsTableVo> tableEdit(@RequestBody @Valid LhDsTableEditParam param) {
        return CommonResult.data(datasourceService.tableEdit(param));
    }

    @Operation(summary = "?????")
    @CommonLog("????????")
    @PostMapping("/lh/datasource/table/delete")
    public CommonResult<String> tableDelete(@RequestBody @Valid @NotEmpty List<LhDsTableIdParam> ids) {
        datasourceService.tableDelete(ids);
        return CommonResult.ok();
    }

    @Operation(summary = "?????")
    @CommonLog("????????")
    @PostMapping("/lh/datasource/table/sync")
    public CommonResult<Map<String, Object>> tableSync(@RequestBody @Valid LhDatasourceIdParam param) {
        return CommonResult.data(datasourceService.tableSync(param));
    }

    @Operation(summary = "???????")
    @CommonLog("??????????")
    @PostMapping("/lh/datasource/table/batchSync")
    public CommonResult<Map<String, Object>> batchSyncTables(
            @RequestBody @Valid @NotEmpty List<LhDatasourceIdParam> ids) {
        return CommonResult.data(datasourceService.batchSyncTables(ids));
    }
}
