/*
 * Copyright [2022] [https://www.xiaonuo.vip]
 *
 * Snowy??APACHE LICENSE 2.0??????????????????????
 *
 * 1.?????????????LICENSE???
 * 2.????????Snowy??????????
 * 3.?????????????????????????????????????????
 * 4.?????????????? https://www.xiaonuo.vip
 * 5.????????????????????????xiaonuobase@qq.com?????
 * 6.?????????????????????????Snowy??????????????????? https://www.xiaonuo.vip
 */
package vip.xiaonuo.lh.modular.engine.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import vip.xiaonuo.common.annotation.CommonLog;
import vip.xiaonuo.common.pojo.CommonResult;
import vip.xiaonuo.lh.core.engine.DsClient;
import vip.xiaonuo.lh.core.engine.FlinkClient;
import vip.xiaonuo.lh.core.engine.OpenMetadataClient;

import java.util.List;
import java.util.Map;

/**
 * ???? / ??????????????????
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Tag(name = "?????????")
@SaIgnore
@RestController
@Validated
public class LhEngineController {

    @Resource
    private FlinkClient flinkClient;
    @Resource
    private DsClient dsClient;
    @Resource
    private OpenMetadataClient openMetadataClient;

    @Operation(summary = "Flink????")
    @GetMapping("/lh/engine/flinkJobs")
    public CommonResult<List<Map<String, Object>>> flinkJobs() {
        return CommonResult.data(flinkClient.listJobs());
    }

    @Operation(summary = "DS?????")
    @GetMapping("/lh/engine/dsWorkflows")
    public CommonResult<List<Map<String, Object>>> dsWorkflows() {
        return CommonResult.data(dsClient.listWorkflows());
    }

    @Operation(summary = "????")
    @GetMapping("/lh/engine/health")
    public CommonResult<List<Map<String, Object>>> health() {
        return CommonResult.data(List.of(
                flinkClient.health(),
                dsClient.health(),
                openMetadataClient.health()
        ));
    }

    @Operation(summary = "OM???")
    @GetMapping("/lh/engine/om/tables")
    public CommonResult<Map<String, Object>> omTables(
            @RequestParam(defaultValue = "10") int limit) {
        return CommonResult.data(openMetadataClient.listTables(limit));
    }

    @Operation(summary = "??OM Bot Token")
    @CommonLog("??OpenMetadata Bot Token")
    @PostMapping("/lh/engine/om/rotateBotToken")
    public CommonResult<String> rotateOmBotToken(@RequestBody Map<String, String> body) {
        String token = body.get("token");
        String email = body.get("email");
        openMetadataClient.rotateBotToken(token, email);
        return CommonResult.ok();
    }
}
