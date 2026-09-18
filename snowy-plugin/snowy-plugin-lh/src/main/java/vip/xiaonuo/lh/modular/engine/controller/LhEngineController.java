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
package vip.xiaonuo.lh.modular.engine.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
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
 * 计算引擎 / 外部组件适配控制器
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Tag(name = "计算引擎适配控制器")
@RestController
@Validated
public class LhEngineController {

    @Resource
    private FlinkClient flinkClient;
    @Resource
    private DsClient dsClient;
    @Resource
    private OpenMetadataClient openMetadataClient;

    /**
     * Flink 作业列表
     */
    @Operation(summary = "Flink作业列表")
    @SaCheckPermission("/lh/engine/flinkJobs")
    @GetMapping("/lh/engine/flinkJobs")
    public CommonResult<List<Map<String, Object>>> flinkJobs() {
        return CommonResult.data(flinkClient.listJobs());
    }

    /**
     * DS 工作流列表
     */
    @Operation(summary = "DS工作流列表")
    @SaCheckPermission("/lh/engine/dsWorkflows")
    @GetMapping("/lh/engine/dsWorkflows")
    public CommonResult<List<Map<String, Object>>> dsWorkflows() {
        return CommonResult.data(dsClient.listWorkflows());
    }

    /**
     * 组件健康（Flink / DS / OM）
     */
    @Operation(summary = "组件健康")
    @SaCheckPermission("/lh/engine/health")
    @GetMapping("/lh/engine/health")
    public CommonResult<List<Map<String, Object>>> health() {
        return CommonResult.data(List.of(
                flinkClient.health(),
                dsClient.health(),
                openMetadataClient.health()
        ));
    }

    /**
     * OpenMetadata 表列表探测（Bot Token）
     */
    @Operation(summary = "OM表列表")
    @SaCheckPermission("/lh/engine/om/tables")
    @GetMapping("/lh/engine/om/tables")
    public CommonResult<Map<String, Object>> omTables(
            @RequestParam(defaultValue = "10") int limit) {
        return CommonResult.data(openMetadataClient.listTables(limit));
    }

    /**
     * 写入 / 轮换 OM Bot Token 到 Vault
     */
    @Operation(summary = "轮换OM Bot Token")
    @CommonLog("轮换OpenMetadata Bot Token")
    @SaCheckPermission("/lh/engine/om/rotateBotToken")
    @PostMapping("/lh/engine/om/rotateBotToken")
    public CommonResult<String> rotateOmBotToken(@RequestBody Map<String, String> body) {
        String token = body.get("token");
        String email = body.get("email");
        openMetadataClient.rotateBotToken(token, email);
        return CommonResult.ok();
    }
}
