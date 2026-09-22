package vip.xiaonuo.lh.modular.observability.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.xiaonuo.common.pojo.CommonResult;
import vip.xiaonuo.lh.modular.observability.service.LhObsCostService;

import java.util.Map;

/**
 * 可观测成本 / 用量（§24.3 · querygov 成本卡 group=ws）
 * <p>主路径 {@code /lh/observability/costs}；兼容全栈草案 {@code /api/observability/costs}
 * 与 querygov 旁路 {@code /lh/compute/query/gov/costs}。
 */
@Tag(name = "可观测成本")
@RestController
@Validated
public class LhObsCostController {

    @Resource
    private LhObsCostService lhObsCostService;

    @Operation(summary = "用量/成本按 group 聚合（验收 group=ws）")
    @GetMapping({
            "/lh/observability/costs",
            "/api/observability/costs",
            "/lh/compute/query/gov/costs",
            "/lh/query/gov/costs"
    })
    public CommonResult<Map<String, Object>> costs(
            @RequestParam(required = false, defaultValue = "30d") String range,
            @RequestParam(required = false, defaultValue = "ws") String group,
            @RequestParam(required = false) String ws) {
        return CommonResult.data(lhObsCostService.costs(range, group, ws));
    }

    @Operation(summary = "用量按 group 聚合（与 costs 同源，不含单价说明时可只读 items）")
    @GetMapping({
            "/lh/observability/usage",
            "/api/observability/usage"
    })
    public CommonResult<Map<String, Object>> usage(
            @RequestParam(required = false, defaultValue = "30d") String range,
            @RequestParam(required = false, defaultValue = "ws") String group,
            @RequestParam(required = false) String ws) {
        return CommonResult.data(lhObsCostService.costs(range, group, ws));
    }
}
