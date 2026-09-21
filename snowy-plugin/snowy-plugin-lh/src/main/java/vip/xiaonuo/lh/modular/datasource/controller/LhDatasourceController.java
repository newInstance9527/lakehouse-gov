/*
 * Copyright [2022] [https://www.xiaonuo.vip]
 *
 * Snowy采用APACHE LICENSE 2.0开源协议，您在使用过程中，需要注意以下几点：
 *
 * 1.请不要删除和修改根目录下的LICENSE文件。
 * 2.请不要删除和修改Snowy源码头部的版权声明。
 * 3.本项目代码可免费商业使用，商业使用请保留源码和相关描述文件的项目出处，作者声明等。
 * 4.分发源码时候，请注明软件出处 https://www.xiaonuo.vip
 * 5.不可二次分发开源参与同类竞品，如有想法可联系团队xiaonuobase@qq.com商议合作。
 * 6.若您的项目无法满足以上几点，需要更多功能代码，获取Snowy商业授权许可，请在官网购买授权，地址为 https://www.xiaonuo.vip
 */
package vip.xiaonuo.lh.modular.datasource.controller;

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
import vip.xiaonuo.lh.modular.datasource.result.LhMetaColumnVo;
import vip.xiaonuo.lh.modular.datasource.result.LhMetaObjectVo;
import vip.xiaonuo.lh.modular.datasource.service.LhDatasourceService;

import java.util.List;
import java.util.Map;

/**
 * 数据源中心控制器（入参/出参对齐前端门户；需登录（Sa-Token））
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Tag(name = "数据源中心控制器")
@RestController
@Validated
public class LhDatasourceController {

    @Resource
    private LhDatasourceService datasourceService;

    @Operation(summary = "数据源分页")
    @GetMapping("/lh/datasource/page")
    public CommonResult<Page<LhDatasourceVo>> page(LhDatasourcePageParam param) {
        return CommonResult.data(datasourceService.page(param));
    }

    @Operation(summary = "新增数据源")
    @CommonLog("新增数据源")
    @PostMapping("/lh/datasource/add")
    public CommonResult<LhDatasourceVo> add(@RequestBody @Valid LhDatasourceAddParam param) {
        return CommonResult.data(datasourceService.add(param));
    }

    @Operation(summary = "编辑数据源")
    @CommonLog("编辑数据源")
    @PostMapping("/lh/datasource/edit")
    public CommonResult<LhDatasourceVo> edit(@RequestBody @Valid LhDatasourceEditParam param) {
        return CommonResult.data(datasourceService.edit(param));
    }

    @Operation(summary = "删除数据源")
    @CommonLog("删除数据源")
    @PostMapping("/lh/datasource/delete")
    public CommonResult<String> delete(@RequestBody @Valid @NotEmpty List<LhDatasourceIdParam> ids) {
        datasourceService.delete(ids);
        return CommonResult.ok();
    }

    @Operation(summary = "数据源详情")
    @GetMapping("/lh/datasource/detail")
    public CommonResult<LhDatasourceVo> detail(@Valid LhDatasourceIdParam param) {
        return CommonResult.data(datasourceService.detail(param));
    }

    @Operation(summary = "连通性测试")
    @PostMapping("/lh/datasource/test")
    public CommonResult<Map<String, Object>> test(@RequestBody LhDatasourceTestParam param) {
        return CommonResult.data(datasourceService.test(param));
    }

    @Operation(summary = "Schema预览")
    @GetMapping("/lh/datasource/previewSchema")
    public CommonResult<Map<String, Object>> previewSchema(@Valid LhDatasourceIdParam param) {
        return CommonResult.data(datasourceService.previewSchema(param));
    }

    @Operation(summary = "元数据：Schema 列表")
    @GetMapping("/lh/datasource/meta/schemas")
    public CommonResult<List<String>> metaSchemas(@Valid LhDatasourceMetaParam param) {
        return CommonResult.data(datasourceService.listMetaSchemas(param));
    }

    @Operation(summary = "元数据：表列表")
    @GetMapping("/lh/datasource/meta/tables")
    public CommonResult<List<LhMetaObjectVo>> metaTables(@Valid LhDatasourceMetaParam param) {
        return CommonResult.data(datasourceService.listMetaTables(param));
    }

    @Operation(summary = "元数据：视图列表")
    @GetMapping("/lh/datasource/meta/views")
    public CommonResult<List<LhMetaObjectVo>> metaViews(@Valid LhDatasourceMetaParam param) {
        return CommonResult.data(datasourceService.listMetaViews(param));
    }

    @Operation(summary = "元数据：列列表")
    @GetMapping("/lh/datasource/meta/columns")
    public CommonResult<List<LhMetaColumnVo>> metaColumns(@Valid LhDatasourceMetaParam param) {
        return CommonResult.data(datasourceService.listMetaColumns(param));
    }

    @Operation(summary = "更新用途")
    @CommonLog("更新数据源用途")
    @PostMapping("/lh/datasource/purposes")
    public CommonResult<String> purposes(@RequestBody @Valid LhDatasourcePurposesParam param) {
        datasourceService.updatePurposes(param);
        return CommonResult.ok();
    }

    @Operation(summary = "消费者绑定列表")
    @GetMapping("/lh/datasource/bindings")
    public CommonResult<List<LhConsumerBinding>> bindings(@Valid LhDatasourceIdParam param) {
        return CommonResult.data(datasourceService.bindings(param));
    }

    @Operation(summary = "轮换凭证")
    @CommonLog("轮换数据源凭证")
    @PostMapping("/lh/datasource/rotateCred")
    public CommonResult<String> rotateCred(@RequestBody @Valid LhDatasourceIdParam param) {
        datasourceService.rotateCred(param);
        return CommonResult.ok();
    }

    @Operation(summary = "DAG可用源")
    @GetMapping("/lh/datasource/listForDag")
    public CommonResult<List<LhDatasourceVo>> listForDag() {
        return CommonResult.data(datasourceService.listForDag());
    }

    @Operation(summary = "Superset投影(仅Trino)")
    @GetMapping("/lh/datasource/supersetProjection")
    public CommonResult<List<LhDatasourceVo>> supersetProjection() {
        return CommonResult.data(datasourceService.supersetProjection());
    }

    @Operation(summary = "SQLREST 可选/可投影数据源")
    @GetMapping("/lh/datasource/listForSqlrest")
    public CommonResult<List<Map<String, Object>>> listForSqlrest() {
        return CommonResult.data(datasourceService.listForSqlrest());
    }

    @Operation(summary = "投影到 SQLREST Manager")
    @CommonLog("投影数据源到 SQLREST")
    @PostMapping("/lh/datasource/projectToSqlrest")
    public CommonResult<Map<String, Object>> projectToSqlrest(
            @RequestBody(required = false) List<LhDatasourceIdParam> ids) {
        return CommonResult.data(datasourceService.projectToSqlrest(ids));
    }

    @Operation(summary = "批量导入")
    @CommonLog("批量导入数据源")
    @PostMapping("/lh/datasource/batchImport")
    public CommonResult<String> batchImport(@RequestBody @Valid LhDatasourceBatchImportParam param) {
        datasourceService.batchImport(param);
        return CommonResult.ok();
    }

    @Operation(summary = "启停切换")
    @CommonLog("启停数据源")
    @PostMapping("/lh/datasource/toggleStatus")
    public CommonResult<Map<String, Object>> toggleStatus(@RequestBody @Valid LhDatasourceToggleParam param) {
        return CommonResult.data(datasourceService.toggleStatus(param));
    }

    @Operation(summary = "KPI统计")
    @GetMapping("/lh/datasource/kpi")
    public CommonResult<Map<String, Object>> kpi() {
        return CommonResult.data(datasourceService.kpi());
    }

    @Operation(summary = "类型选项")
    @GetMapping("/lh/datasource/typeOptions")
    public CommonResult<List<Map<String, Object>>> typeOptions() {
        return CommonResult.data(datasourceService.typeOptions());
    }

    @Operation(summary = "动态表单Schema")
    @GetMapping("/lh/datasource/formSchema")
    public CommonResult<Map<String, Object>> formSchema(
            @RequestParam(value = "type", required = false) String type) {
        return CommonResult.data(datasourceService.formSchema(type));
    }

    @Operation(summary = "表清单分页")
    @GetMapping("/lh/datasource/table/page")
    public CommonResult<Page<LhDsTableVo>> tablePage(@Valid LhDsTablePageParam param) {
        return CommonResult.data(datasourceService.tablePage(param));
    }

    @Operation(summary = "表清单新增")
    @CommonLog("新增数据源表清单")
    @PostMapping("/lh/datasource/table/add")
    public CommonResult<LhDsTableVo> tableAdd(@RequestBody @Valid LhDsTableAddParam param) {
        return CommonResult.data(datasourceService.tableAdd(param));
    }

    @Operation(summary = "表清单编辑")
    @CommonLog("编辑数据源表清单")
    @PostMapping("/lh/datasource/table/edit")
    public CommonResult<LhDsTableVo> tableEdit(@RequestBody @Valid LhDsTableEditParam param) {
        return CommonResult.data(datasourceService.tableEdit(param));
    }

    @Operation(summary = "表清单删除")
    @CommonLog("删除数据源表清单")
    @PostMapping("/lh/datasource/table/delete")
    public CommonResult<String> tableDelete(@RequestBody @Valid @NotEmpty List<LhDsTableIdParam> ids) {
        datasourceService.tableDelete(ids);
        return CommonResult.ok();
    }


    @Operation(summary = "登记前发现表清单")
    @PostMapping("/lh/datasource/table/discover")
    public CommonResult<Map<String, Object>> tableDiscover(@RequestBody LhDatasourceTestParam param) {
        return CommonResult.data(datasourceService.tableDiscover(param));
    }

    @Operation(summary = "同步表清单")
    @CommonLog("同步数据源表清单")
    @PostMapping("/lh/datasource/table/sync")
    public CommonResult<Map<String, Object>> tableSync(@RequestBody @Valid LhDatasourceIdParam param) {
        return CommonResult.data(datasourceService.tableSync(param));
    }

    @Operation(summary = "批量同步表清单")
    @CommonLog("批量同步数据源表清单")
    @PostMapping("/lh/datasource/table/batchSync")
    public CommonResult<Map<String, Object>> batchSyncTables(
            @RequestBody @Valid @NotEmpty List<LhDatasourceIdParam> ids) {
        return CommonResult.data(datasourceService.batchSyncTables(ids));
    }

    @Operation(summary = "投影到 Gravitino Catalog（空=全部可映射）")
    @CommonLog("数据源投影Grav")
    @PostMapping("/lh/datasource/projectToGravitino")
    public CommonResult<Map<String, Object>> projectToGravitino(
            @RequestBody(required = false) List<LhDatasourceIdParam> ids) {
        return CommonResult.data(datasourceService.projectToGravitino(ids));
    }
}
