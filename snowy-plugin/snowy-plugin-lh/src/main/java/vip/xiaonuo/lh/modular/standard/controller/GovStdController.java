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
package vip.xiaonuo.lh.modular.standard.controller;

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
import vip.xiaonuo.lh.modular.standard.param.GovStdCodeUpsertParam;
import vip.xiaonuo.lh.modular.standard.param.GovStdFieldUpsertParam;
import vip.xiaonuo.lh.modular.standard.param.GovStdIdParam;
import vip.xiaonuo.lh.modular.standard.param.GovStdMappingUpsertParam;
import vip.xiaonuo.lh.modular.standard.param.GovStdNamingUpsertParam;
import vip.xiaonuo.lh.modular.standard.param.GovStdPageParam;
import vip.xiaonuo.lh.modular.standard.result.GovStdCodeVo;
import vip.xiaonuo.lh.modular.standard.result.GovStdDetectVo;
import vip.xiaonuo.lh.modular.standard.result.GovStdFieldVo;
import vip.xiaonuo.lh.modular.standard.result.GovStdMappingVo;
import vip.xiaonuo.lh.modular.standard.result.GovStdNamingVo;
import vip.xiaonuo.lh.modular.standard.service.GovStdService;

import java.util.Map;

/**
 * 数据标准控制器（对齐 doc/数据标准.md；需登录（Sa-Token））
 *
 * @author lakehouse
 * @date 2026/9/19
 */
@Tag(name = "数据标准控制器")
@RestController
@Validated
public class GovStdController {

    @Resource
    private GovStdService govStdService;

    @Operation(summary = "KPI 概览")
    @GetMapping("/lh/standard/overview")
    public CommonResult<Map<String, Object>> overview(@RequestParam(required = false) String ws) {
        return CommonResult.data(govStdService.overview(ws));
    }

    @Operation(summary = "标准字段分页")
    @GetMapping("/lh/standard/fields")
    public CommonResult<Page<GovStdFieldVo>> pageFields(GovStdPageParam param) {
        return CommonResult.data(govStdService.pageFields(param));
    }

    @Operation(summary = "注册/更新标准字段")
    @CommonLog("注册标准字段")
    @PostMapping("/lh/standard/fields")
    public CommonResult<GovStdFieldVo> upsertField(@RequestBody @Valid GovStdFieldUpsertParam param) {
        return CommonResult.data(govStdService.upsertField(param));
    }

    @Operation(summary = "删除标准字段")
    @CommonLog("删除标准字段")
    @PostMapping("/lh/standard/fields/delete")
    public CommonResult<String> deleteField(@RequestBody @Valid GovStdIdParam param) {
        govStdService.deleteField(param);
        return CommonResult.ok();
    }

    @Operation(summary = "标准码值分页")
    @GetMapping("/lh/standard/codes")
    public CommonResult<Page<GovStdCodeVo>> pageCodes(GovStdPageParam param) {
        return CommonResult.data(govStdService.pageCodes(param));
    }

    @Operation(summary = "注册/更新标准码值")
    @CommonLog("注册标准码值")
    @PostMapping("/lh/standard/codes")
    public CommonResult<GovStdCodeVo> upsertCode(@RequestBody @Valid GovStdCodeUpsertParam param) {
        return CommonResult.data(govStdService.upsertCode(param));
    }

    @Operation(summary = "删除标准码值")
    @CommonLog("删除标准码值")
    @PostMapping("/lh/standard/codes/delete")
    public CommonResult<String> deleteCode(@RequestBody @Valid GovStdIdParam param) {
        govStdService.deleteCode(param);
        return CommonResult.ok();
    }

    @Operation(summary = "命名规范分页")
    @GetMapping("/lh/standard/namings")
    public CommonResult<Page<GovStdNamingVo>> pageNamings(GovStdPageParam param) {
        return CommonResult.data(govStdService.pageNamings(param));
    }

    @Operation(summary = "注册/更新命名规范")
    @CommonLog("注册命名规范")
    @PostMapping("/lh/standard/namings")
    public CommonResult<GovStdNamingVo> upsertNaming(@RequestBody @Valid GovStdNamingUpsertParam param) {
        return CommonResult.data(govStdService.upsertNaming(param));
    }

    @Operation(summary = "删除命名规范")
    @CommonLog("删除命名规范")
    @PostMapping("/lh/standard/namings/delete")
    public CommonResult<String> deleteNaming(@RequestBody @Valid GovStdIdParam param) {
        govStdService.deleteNaming(param);
        return CommonResult.ok();
    }

    @Operation(summary = "源到标准映射分页")
    @GetMapping("/lh/standard/mappings")
    public CommonResult<Page<GovStdMappingVo>> pageMappings(GovStdPageParam param) {
        return CommonResult.data(govStdService.pageMappings(param));
    }

    @Operation(summary = "注册/更新源到标准映射")
    @CommonLog("注册标准映射")
    @PostMapping("/lh/standard/mappings")
    public CommonResult<GovStdMappingVo> upsertMapping(@RequestBody @Valid GovStdMappingUpsertParam param) {
        return CommonResult.data(govStdService.upsertMapping(param));
    }

    @Operation(summary = "删除源到标准映射")
    @CommonLog("删除标准映射")
    @PostMapping("/lh/standard/mappings/delete")
    public CommonResult<String> deleteMapping(@RequestBody @Valid GovStdIdParam param) {
        govStdService.deleteMapping(param);
        return CommonResult.ok();
    }

    @Operation(summary = "落地检测结果分页（只读）")
    @GetMapping("/lh/standard/detects")
    public CommonResult<Page<GovStdDetectVo>> pageDetects(GovStdPageParam param) {
        return CommonResult.data(govStdService.pageDetects(param));
    }

    @Operation(summary = "运行落地检测（按映射/字段/码值抽检并写入结果流水）")
    @CommonLog("运行标准落地检测")
    @PostMapping("/lh/standard/detects/run")
    public CommonResult<Map<String, Object>> runLandingDetect(@RequestParam(required = false) String ws) {
        return CommonResult.data(govStdService.runLandingDetect(ws));
    }

    @Operation(summary = "域/层级/状态字典")
    @GetMapping("/lh/standard/metaOptions")
    public CommonResult<Map<String, Object>> metaOptions() {
        return CommonResult.data(govStdService.metaOptions());
    }
}
