package vip.xiaonuo.lh.modular.workspace.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import vip.xiaonuo.common.annotation.CommonLog;
import vip.xiaonuo.common.pojo.CommonResult;
import vip.xiaonuo.lh.modular.workspace.param.GovWsCreateParam;
import vip.xiaonuo.lh.modular.workspace.param.GovWsCurrentParam;
import vip.xiaonuo.lh.modular.workspace.param.GovWsMemberItemParam;
import vip.xiaonuo.lh.modular.workspace.param.GovWsMembersReplaceParam;
import vip.xiaonuo.lh.modular.workspace.result.GovWsMemberVo;
import vip.xiaonuo.lh.modular.workspace.result.GovWsQuotaVo;
import vip.xiaonuo.lh.modular.workspace.result.GovWsVo;
import vip.xiaonuo.lh.modular.workspace.service.GovWsService;

import java.util.List;
import java.util.Map;

/**
 * 工作空间 API（对齐 doc/工作空间.md · /lh/workspace/*）
 */
@Tag(name = "工作空间")
@RestController
@Validated
public class GovWsController {

    @Resource
    private GovWsService govWsService;

    @Operation(summary = "KPI 概览")
    @GetMapping("/lh/workspace/overview")
    public CommonResult<Map<String, Object>> overview() {
        return CommonResult.data(govWsService.overview());
    }

    @Operation(summary = "空间列表")
    @GetMapping("/lh/workspace/spaces")
    public CommonResult<List<GovWsVo>> listSpaces() {
        return CommonResult.data(govWsService.listSpaces());
    }

    @Operation(summary = "空间详情")
    @GetMapping("/lh/workspace/spaces/{code}")
    public CommonResult<GovWsVo> detail(@PathVariable("code") String code) {
        return CommonResult.data(govWsService.detail(code));
    }

    @Operation(summary = "新建空间（不建 Catalog）")
    @CommonLog("新建工作空间")
    @PostMapping("/lh/workspace/spaces")
    public CommonResult<GovWsVo> create(@RequestBody @Valid GovWsCreateParam param) {
        return CommonResult.data(govWsService.create(param));
    }

    @Operation(summary = "归档空间")
    @CommonLog("归档工作空间")
    @PostMapping("/lh/workspace/spaces/{code}/archive")
    public CommonResult<GovWsVo> archive(@PathVariable("code") String code) {
        return CommonResult.data(govWsService.archive(code));
    }

    @Operation(summary = "成员列表")
    @GetMapping("/lh/workspace/spaces/{code}/members")
    public CommonResult<List<GovWsMemberVo>> members(@PathVariable("code") String code) {
        return CommonResult.data(govWsService.listMembers(code));
    }

    @Operation(summary = "全量替换成员")
    @CommonLog("替换工作空间成员")
    @PutMapping("/lh/workspace/spaces/{code}/members")
    public CommonResult<List<GovWsMemberVo>> replaceMembers(
            @PathVariable("code") String code,
            @RequestBody GovWsMembersReplaceParam param) {
        return CommonResult.data(govWsService.replaceMembers(code, param));
    }

    @Operation(summary = "添加成员")
    @CommonLog("添加工作空间成员")
    @PostMapping("/lh/workspace/spaces/{code}/members")
    public CommonResult<GovWsMemberVo> addMember(
            @PathVariable("code") String code,
            @RequestBody @Valid GovWsMemberItemParam param) {
        return CommonResult.data(govWsService.addMember(code, param));
    }

    @Operation(summary = "移除成员")
    @CommonLog("移除工作空间成员")
    @DeleteMapping("/lh/workspace/spaces/{code}/members/{memberId}")
    public CommonResult<String> removeMember(
            @PathVariable("code") String code,
            @PathVariable("memberId") String memberId) {
        govWsService.removeMember(code, memberId);
        return CommonResult.ok();
    }

    @Operation(summary = "空间配额")
    @GetMapping("/lh/workspace/spaces/{code}/quota")
    public CommonResult<GovWsQuotaVo> quota(@PathVariable("code") String code) {
        return CommonResult.data(govWsService.quota(code));
    }

    @Operation(summary = "全部配额")
    @GetMapping("/lh/workspace/quotas")
    public CommonResult<List<GovWsQuotaVo>> quotas() {
        return CommonResult.data(govWsService.listQuotas());
    }

    @Operation(summary = "当前协作上下文")
    @GetMapping("/lh/workspace/current")
    public CommonResult<Map<String, Object>> getCurrent() {
        return CommonResult.data(govWsService.getCurrent());
    }

    @Operation(summary = "设置当前协作上下文")
    @CommonLog("切换工作空间上下文")
    @PutMapping("/lh/workspace/current")
    public CommonResult<Map<String, Object>> setCurrent(@RequestBody @Valid GovWsCurrentParam param) {
        return CommonResult.data(govWsService.setCurrent(param));
    }
}
