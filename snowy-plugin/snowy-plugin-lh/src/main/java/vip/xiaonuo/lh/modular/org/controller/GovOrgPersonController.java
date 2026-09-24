package vip.xiaonuo.lh.modular.org.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
import vip.xiaonuo.lh.modular.org.param.GovOrgPersonSaveParam;
import vip.xiaonuo.lh.modular.org.result.GovOrgPersonVo;
import vip.xiaonuo.lh.modular.org.service.GovOrgPersonService;

import java.util.List;

/**
 * 部门非系统人员（对齐 doc/部门管理.md · /lh/org/person）
 */
@Tag(name = "部门非系统人员")
@RestController
@Validated
public class GovOrgPersonController {

    @Resource
    private GovOrgPersonService govOrgPersonService;

    @Operation(summary = "按部门列出非系统人员（默认含下级部门）")
    @GetMapping("/lh/org/person")
    public CommonResult<List<GovOrgPersonVo>> list(
            @RequestParam("orgId") @NotBlank(message = "orgId不能为空") String orgId,
            @RequestParam(value = "kw", required = false) String kw,
            @RequestParam(value = "includeChild", required = false, defaultValue = "true") Boolean includeChild) {
        return CommonResult.data(govOrgPersonService.listByOrg(orgId, kw, Boolean.TRUE.equals(includeChild)));
    }

    @Operation(summary = "新建非系统人员")
    @CommonLog("新建部门非系统人员")
    @PostMapping("/lh/org/person")
    public CommonResult<GovOrgPersonVo> create(@RequestBody @Valid GovOrgPersonSaveParam param) {
        return CommonResult.data(govOrgPersonService.create(param));
    }

    @Operation(summary = "更新非系统人员")
    @CommonLog("更新部门非系统人员")
    @PutMapping("/lh/org/person/{id}")
    public CommonResult<GovOrgPersonVo> update(
            @PathVariable("id") String id,
            @RequestBody @Valid GovOrgPersonSaveParam param) {
        return CommonResult.data(govOrgPersonService.update(id, param));
    }

    @Operation(summary = "删除非系统人员")
    @CommonLog("删除部门非系统人员")
    @DeleteMapping("/lh/org/person/{id}")
    public CommonResult<String> remove(@PathVariable("id") String id) {
        govOrgPersonService.remove(id);
        return CommonResult.ok();
    }
}
