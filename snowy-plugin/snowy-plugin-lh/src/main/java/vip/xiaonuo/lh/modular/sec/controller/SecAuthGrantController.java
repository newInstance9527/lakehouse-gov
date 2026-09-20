package vip.xiaonuo.lh.modular.sec.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.xiaonuo.common.pojo.CommonResult;
import vip.xiaonuo.lh.modular.sec.service.SecAuthGrantService;

@Tag(name = "表级授权")
@RestController
@Validated
public class SecAuthGrantController {

    @Resource
    private SecAuthGrantService secAuthGrantService;

    @Operation(summary = "检查当前用户是否有资产表级读权限")
    @GetMapping("/lh/sec/grants/check")
    public CommonResult<Boolean> check(@RequestParam @NotBlank String assetId) {
        return CommonResult.data(secAuthGrantService.hasTableReadGrant(assetId));
    }
}
