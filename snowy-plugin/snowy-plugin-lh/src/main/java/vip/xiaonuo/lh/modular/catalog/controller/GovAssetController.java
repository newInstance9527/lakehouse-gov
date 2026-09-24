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
package vip.xiaonuo.lh.modular.catalog.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import vip.xiaonuo.common.annotation.CommonLog;
import vip.xiaonuo.common.pojo.CommonResult;
import vip.xiaonuo.lh.modular.catalog.param.GovAssetAddParam;
import vip.xiaonuo.lh.modular.catalog.param.GovAssetEditParam;
import vip.xiaonuo.lh.modular.catalog.param.GovAssetIdParam;
import vip.xiaonuo.lh.modular.catalog.param.GovAssetMetaParam;
import vip.xiaonuo.lh.modular.catalog.param.GovAssetPageParam;
import vip.xiaonuo.lh.modular.catalog.param.GovAssetPreviewParam;
import vip.xiaonuo.lh.modular.catalog.result.GovAssetSourceVo;
import vip.xiaonuo.lh.modular.catalog.result.GovAssetVo;
import vip.xiaonuo.lh.modular.catalog.service.GovAssetService;

import java.util.List;
import java.util.Map;

/**
 * 资产目录控制器（对齐 doc/资产目录.md；需登录（Sa-Token））
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Tag(name = "资产目录控制器")
@RestController
@Validated
public class GovAssetController {

    @Resource
    private GovAssetService govAssetService;

    @Operation(summary = "资产分页")
    @GetMapping("/lh/catalog/assets")
    public CommonResult<Page<GovAssetVo>> page(GovAssetPageParam param) {
        return CommonResult.data(govAssetService.page(param));
    }

    @Operation(summary = "资产详情")
    @GetMapping("/lh/catalog/assets/detail")
    public CommonResult<GovAssetVo> detail(@Valid GovAssetIdParam param) {
        return CommonResult.data(govAssetService.detail(param));
    }

    @Operation(summary = "注册资产")
    @CommonLog("注册资产")
    @PostMapping("/lh/catalog/assets")
    public CommonResult<GovAssetVo> add(@RequestBody @Valid GovAssetAddParam param) {
        return CommonResult.data(govAssetService.add(param));
    }

    @Operation(summary = "编辑资产门户字段")
    @CommonLog("编辑资产")
    @PostMapping("/lh/catalog/assets/edit")
    public CommonResult<GovAssetVo> edit(@RequestBody @Valid GovAssetEditParam param) {
        return CommonResult.data(govAssetService.edit(param));
    }

    @Operation(summary = "删除资产（软删）")
    @CommonLog("删除资产")
    @PostMapping("/lh/catalog/assets/delete")
    public CommonResult<Map<String, Object>> delete(@RequestBody @Valid GovAssetIdParam param) {
        return CommonResult.data(govAssetService.delete(param));
    }

    @Operation(summary = "资产关联数据源")
    @GetMapping("/lh/catalog/assets/sources")
    public CommonResult<List<GovAssetSourceVo>> sources(@Valid GovAssetIdParam param) {
        return CommonResult.data(govAssetService.sources(param));
    }

    @Operation(summary = "刷新对齐 OM/门户清单")
    @CommonLog("刷新资产对齐")
    @PostMapping("/lh/catalog/assets/refresh")
    public CommonResult<Map<String, Object>> refresh(@RequestBody @Valid GovAssetIdParam param) {
        return CommonResult.data(govAssetService.refresh(param));
    }

    @Operation(summary = "列结构（Grav优先，OM回退）")
    @GetMapping("/lh/catalog/assets/schema")
    public CommonResult<Map<String, Object>> schema(@Valid GovAssetIdParam param) {
        return CommonResult.data(govAssetService.schema(param));
    }

    @Operation(summary = "写OM人读元数据（description/tags）")
    @CommonLog("写资产OM元数据")
    @PostMapping("/lh/catalog/assets/meta")
    public CommonResult<Map<String, Object>> updateMeta(@RequestBody @Valid GovAssetMetaParam param) {
        return CommonResult.data(govAssetService.updateMeta(param));
    }

    @Operation(summary = "数据预览（按源类型 Reader；超管/已授权）")
    @GetMapping("/lh/catalog/assets/preview")
    public CommonResult<Map<String, Object>> preview(@Valid GovAssetPreviewParam param) {
        return CommonResult.data(govAssetService.preview(param));
    }

    @Operation(summary = "分层/域等字典")
    @GetMapping("/lh/catalog/metaOptions")
    public CommonResult<Map<String, Object>> metaOptions() {
        return CommonResult.data(govAssetService.metaOptions());
    }

    @Operation(summary = "元数据漂移对账（门户↔Grav/OM；error 摘金+degraded）")
    @CommonLog("资产元数据漂移对账")
    @PostMapping("/lh/catalog/assets/drift/reconcile")
    public CommonResult<Map<String, Object>> reconcileMetaDrift(
            @RequestParam(required = false) String ws,
            @RequestParam(required = false) String assetId) {
        return CommonResult.data(govAssetService.reconcileMetaDrift(ws, assetId));
    }

    @Operation(summary = "打开中的元数据漂移单")
    @GetMapping("/lh/catalog/assets/drift")
    public CommonResult<List<Map<String, Object>>> listMetaDrifts(
            @RequestParam(required = false) String ws,
            @RequestParam(required = false) Integer limit) {
        return CommonResult.data(govAssetService.listMetaDrifts(ws, limit));
    }

    @Operation(summary = "发布到企业共享层（门户可见；不写 Grav）")
    @CommonLog("资产发布企业共享")
    @PostMapping("/lh/catalog/assets/publish-share")
    public CommonResult<GovAssetVo> publishShare(@RequestBody @Valid GovAssetIdParam param) {
        return CommonResult.data(govAssetService.publishShare(param));
    }

    @Operation(summary = "撤回企业共享")
    @CommonLog("资产撤回企业共享")
    @PostMapping("/lh/catalog/assets/unpublish-share")
    public CommonResult<GovAssetVo> unpublishShare(@RequestBody @Valid GovAssetIdParam param) {
        return CommonResult.data(govAssetService.unpublishShare(param));
    }
}
