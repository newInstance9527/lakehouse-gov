package vip.xiaonuo.lh.modular.moduleops.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.xiaonuo.common.pojo.CommonResult;
import vip.xiaonuo.lh.modular.moduleops.service.LhModuleOpsService;

import java.util.List;
import java.util.Map;

/**
 * 治理模块运维（目录 / 质量 / 血缘）：连接参数脱敏 + 探活状态
 */
@Tag(name = "治理模块运维")
@RestController
@Validated
public class LhModuleOpsController {

    @Resource
    private LhModuleOpsService lhModuleOpsService;

    @Operation(summary = "模块运维列表")
    @GetMapping("/lh/module-ops")
    public CommonResult<List<Map<String, Object>>> list(
            @RequestParam(required = false, defaultValue = "all") String module) {
        return CommonResult.data(lhModuleOpsService.listModules(module));
    }

    @Operation(summary = "单模块运维详情")
    @GetMapping("/lh/module-ops/detail")
    public CommonResult<Map<String, Object>> detail(@RequestParam String module) {
        return CommonResult.data(lhModuleOpsService.detail(module));
    }
}
