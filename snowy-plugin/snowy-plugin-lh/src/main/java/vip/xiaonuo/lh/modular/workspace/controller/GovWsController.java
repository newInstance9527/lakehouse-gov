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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.xiaonuo.common.annotation.CommonLog;
import vip.xiaonuo.common.pojo.CommonResult;
import vip.xiaonuo.lh.modular.workspace.param.GovWsCreateParam;
import vip.xiaonuo.lh.modular.workspace.param.GovWsCurrentParam;
import vip.xiaonuo.lh.modular.workspace.param.GovWsMemberItemParam;
import vip.xiaonuo.lh.modular.workspace.param.GovWsMembersReplaceParam;
import vip.xiaonuo.lh.modular.workspace.param.GovWsQuotaUpdateParam;
import vip.xiaonuo.lh.modular.workspace.param.GovWsTagAddParam;
import vip.xiaonuo.lh.modular.workspace.param.GovWsTagsReplaceParam;
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

    @Operation(summary = "归档空间（软删，同 DELETE）")
    @CommonLog("归档工作空间")
    @PostMapping("/lh/workspace/spaces/{code}/archive")
    public CommonResult<GovWsVo> archive(@PathVariable("code") String code) {
        return CommonResult.data(govWsService.archive(code));
    }

    @Operation(summary = "删除空间（软删 status=archived；禁止 default / 最后活跃空间）")
    @CommonLog("删除工作空间")
    @DeleteMapping("/lh/workspace/spaces/{code}")
    public CommonResult<GovWsVo> delete(@PathVariable("code") String code) {
        return CommonResult.data(govWsService.delete(code));
    }

    @Operation(summary = "同步 Gitea 远程（per-ws 仓；自定义公网保留；回写脱敏展示）")
    @CommonLog("同步工作空间 Git 远程")
    @PostMapping("/lh/workspace/spaces/{code}/git-remote/sync")
    public CommonResult<GovWsVo> syncGitRemote(@PathVariable("code") String code) {
        return CommonResult.data(govWsService.syncGitRemote(code));
    }

    @Operation(summary = "追加空间标签（Owner/超管；写入 gov_ws.tags_json）")
    @CommonLog("新增工作空间标签")
    @PostMapping("/lh/workspace/spaces/{code}/tags")
    public CommonResult<GovWsVo> addTag(
            @PathVariable("code") String code,
            @RequestBody @Valid GovWsTagAddParam param) {
        return CommonResult.data(govWsService.addTag(code, param));
    }

    @Operation(summary = "全量替换空间标签（Owner/超管；空列表=清空）")
    @CommonLog("替换工作空间标签")
    @PutMapping("/lh/workspace/spaces/{code}/tags")
    public CommonResult<GovWsVo> replaceTags(
            @PathVariable("code") String code,
            @RequestBody GovWsTagsReplaceParam param) {
        return CommonResult.data(govWsService.replaceTags(code, param));
    }

    @Operation(summary = "移除空间标签（Owner/超管；按文案匹配）")
    @CommonLog("移除工作空间标签")
    @DeleteMapping("/lh/workspace/spaces/{code}/tags")
    public CommonResult<GovWsVo> removeTag(
            @PathVariable("code") String code,
            @RequestParam("text") String text) {
        return CommonResult.data(govWsService.removeTag(code, text));
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

    @Operation(summary = "更新空间配额（Owner/超管；AI 传 0=不限）")
    @CommonLog("更新工作空间配额")
    @PutMapping("/lh/workspace/spaces/{code}/quota")
    public CommonResult<GovWsQuotaVo> updateQuota(
            @PathVariable("code") String code,
            @RequestBody GovWsQuotaUpdateParam param) {
        return CommonResult.data(govWsService.updateQuota(code, param));
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

    @Operation(summary = "配额水位告警（≥60% 提示 / ≥80% 告警）")
    @GetMapping("/lh/workspace/quota-alerts")
    public CommonResult<List<Map<String, Object>>> quotaAlerts() {
        return CommonResult.data(govWsService.listQuotaAlerts());
    }
}
