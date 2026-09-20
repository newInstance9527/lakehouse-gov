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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import vip.xiaonuo.common.annotation.CommonLog;
import vip.xiaonuo.common.pojo.CommonResult;
import vip.xiaonuo.lh.core.engine.DsClient;
import vip.xiaonuo.lh.core.engine.DataxClient;
import vip.xiaonuo.lh.core.engine.FlinkClient;
import vip.xiaonuo.lh.core.engine.GravitinoClient;
import vip.xiaonuo.lh.core.engine.MarquezClient;
import vip.xiaonuo.lh.core.engine.OpenMetadataClient;
import vip.xiaonuo.lh.core.engine.SparkClient;

import java.util.List;
import java.util.Map;

/**
 * 计算引擎 / 外部组件适配控制器（独立前端无登录）
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
    private SparkClient sparkClient;
    @Resource
    private DataxClient dataxClient;
    @Resource
    private DsClient dsClient;
    @Resource
    private OpenMetadataClient openMetadataClient;
    @Resource
    private MarquezClient marquezClient;
    @Resource
    private GravitinoClient gravitinoClient;

    @Operation(summary = "Flink作业列表")
    @GetMapping("/lh/engine/flinkJobs")
    public CommonResult<List<Map<String, Object>>> flinkJobs() {
        return CommonResult.data(flinkClient.listJobs());
    }

    @Operation(summary = "DS工作流列表")
    @GetMapping("/lh/engine/dsWorkflows")
    public CommonResult<List<Map<String, Object>>> dsWorkflows() {
        return CommonResult.data(dsClient.listWorkflows());
    }

    @Operation(summary = "组件健康")
    @GetMapping("/lh/engine/health")
    public CommonResult<List<Map<String, Object>>> health() {
        return CommonResult.data(List.of(
                flinkClient.health(),
                sparkClient.health(),
                dataxClient.health(),
                dsClient.health(),
                openMetadataClient.health(),
                marquezClient.health(),
                gravitinoClient.health()
        ));
    }

    @Operation(summary = "Flink 提交 JAR（soft-fail）")
    @CommonLog("Flink提交JAR")
    @PostMapping("/lh/engine/flink/submitJar")
    public CommonResult<Map<String, Object>> flinkSubmitJar(@RequestBody Map<String, Object> body) {
        return CommonResult.data(flinkClient.submitJar(
                str(body.get("jarId")),
                str(body.get("entryClass")),
                str(body.get("programArgs")),
                body.get("parallelism") instanceof Number n ? n.intValue() : null));
    }

    @Operation(summary = "Flink 作业详情")
    @GetMapping("/lh/engine/flink/job")
    public CommonResult<Map<String, Object>> flinkJob(@RequestParam String jobId) {
        return CommonResult.data(flinkClient.getJob(jobId));
    }

    @Operation(summary = "预览引擎提交脚本")
    @PostMapping("/lh/engine/previewSubmit")
    public CommonResult<Map<String, Object>> previewSubmit(@RequestBody Map<String, Object> body) {
        String engine = str(body.get("engine"));
        String nodeKey = str(body.get("nodeKey"));
        String nodeType = str(body.get("nodeType"));
        String sql = str(body.get("sql"));
        @SuppressWarnings("unchecked")
        Map<String, Object> conf = body.get("conf") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        if ("spark".equalsIgnoreCase(engine)) {
            return CommonResult.data(sparkClient.previewSubmitScript(nodeKey, nodeType, sql, conf));
        }
        if ("datax".equalsIgnoreCase(engine)) {
            return CommonResult.data(dataxClient.previewJob(nodeKey, nodeType, conf));
        }
        return CommonResult.data(flinkClient.previewSubmitScript(nodeKey, nodeType, sql, conf));
    }

    @Operation(summary = "OM表列表")
    @GetMapping("/lh/engine/om/tables")
    public CommonResult<Map<String, Object>> omTables(
            @RequestParam(defaultValue = "10") int limit) {
        return CommonResult.data(openMetadataClient.listTables(limit));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @Operation(summary = "轮换OM Bot Token")
    @CommonLog("轮换OpenMetadata Bot Token")
    @PostMapping("/lh/engine/om/rotateBotToken")
    public CommonResult<String> rotateOmBotToken(@RequestBody Map<String, String> body) {
        String token = body.get("token");
        String email = body.get("email");
        openMetadataClient.rotateBotToken(token, email);
        return CommonResult.ok();
    }
}
