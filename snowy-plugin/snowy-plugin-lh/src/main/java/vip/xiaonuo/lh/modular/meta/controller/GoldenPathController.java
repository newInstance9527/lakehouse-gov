package vip.xiaonuo.lh.modular.meta.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.xiaonuo.common.pojo.CommonResult;
import vip.xiaonuo.lh.modular.meta.service.GoldenPathService;

import java.util.Map;

@Tag(name = "元数据金路径探针")
@RestController
public class GoldenPathController {

    @Resource
    private GoldenPathService goldenPathService;

    @Operation(summary = "M1.4 金路径仓内软探针")
    @GetMapping("/lh/meta/golden-path")
    public CommonResult<Map<String, Object>> goldenPath(@RequestParam(required = false) String ws) {
        return CommonResult.data(goldenPathService.status(ws));
    }
}
