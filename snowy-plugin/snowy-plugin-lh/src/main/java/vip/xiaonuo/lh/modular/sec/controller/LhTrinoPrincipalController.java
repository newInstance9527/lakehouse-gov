package vip.xiaonuo.lh.modular.sec.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import vip.xiaonuo.common.pojo.CommonResult;
import vip.xiaonuo.lh.core.auth.LhLoginUsers;
import vip.xiaonuo.lh.modular.sec.entity.LhTrinoPrincipal;
import vip.xiaonuo.lh.modular.sec.param.LhTrinoPrincipalBindParam;
import vip.xiaonuo.lh.modular.sec.service.LhTrinoPrincipalService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 身份映射。不签发、不查询表权限。
 */
@Tag(name = "Trino 主体映射")
@RestController
@Validated
public class LhTrinoPrincipalController {

    @Resource
    private LhTrinoPrincipalService principalService;

    @Operation(summary = "绑定门户用户到 Gravitino 主体（不授予表权限）")
    @PostMapping("/lh/sec/principals")
    public CommonResult<LhTrinoPrincipal> bind(@RequestBody @Valid LhTrinoPrincipalBindParam param) {
        return CommonResult.data(principalService.bind(param));
    }

    @Operation(summary = "已映射的人类主体")
    @GetMapping("/lh/sec/principals")
    public CommonResult<java.util.List<LhTrinoPrincipal>> list() {
        return CommonResult.data(principalService.listActive());
    }

    @Operation(summary = "当前用户的主体；未映射 mapped=false")
    @GetMapping("/lh/sec/principals/me")
    public CommonResult<Map<String, Object>> me() {
        Map<String, Object> out = new LinkedHashMap<>();
        var user = LhLoginUsers.requireUser();
        LhTrinoPrincipal row = principalService.findActive(user.getId());
        out.put("mapped", row != null);
        out.put("portalAccount", user.getAccount());
        out.put("principal", row);
        return CommonResult.data(out);
    }

    @Operation(summary = "Trino impersonation 片段（无表权限）")
    @GetMapping("/lh/sec/principals/impersonation-rule")
    public CommonResult<Map<String, Object>> impersonationRule() {
        return CommonResult.data(principalService.impersonationFragment());
    }
}
