package vip.xiaonuo.lh.modular.aimodel.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.xiaonuo.common.annotation.CommonLog;
import vip.xiaonuo.common.pojo.CommonResult;
import vip.xiaonuo.lh.modular.aimodel.param.GovAiModelEnableParam;
import vip.xiaonuo.lh.modular.aimodel.param.GovAiModelPageParam;
import vip.xiaonuo.lh.modular.aimodel.param.GovAiModelRotateParam;
import vip.xiaonuo.lh.modular.aimodel.param.GovAiModelUpsertParam;
import vip.xiaonuo.lh.modular.aimodel.param.GovAiRouteUpsertParam;
import vip.xiaonuo.lh.modular.aimodel.result.GovAiModelVo;
import vip.xiaonuo.lh.modular.aimodel.result.GovAiRouteVo;
import vip.xiaonuo.lh.modular.aimodel.service.GovAiModelService;

import java.util.List;
import java.util.Map;

/**
 * AI 模型管理（对齐 doc/AI模型管理.md P0）
 */
@Tag(name = "AI模型管理控制器")
@RestController
@Validated
public class GovAiModelController {

    @Resource
    private GovAiModelService govAiModelService;

    @Operation(summary = "模型 KPI 概览")
    @GetMapping("/lh/ai/models/overview")
    public CommonResult<Map<String, Object>> overview(@RequestParam(required = false) String ws) {
        return CommonResult.data(govAiModelService.overview(ws));
    }

    @Operation(summary = "模型分页列表")
    @GetMapping("/lh/ai/models")
    public CommonResult<Page<GovAiModelVo>> page(GovAiModelPageParam param) {
        return CommonResult.data(govAiModelService.page(param));
    }

    @Operation(summary = "接入模型")
    @CommonLog("接入AI模型")
    @PostMapping("/lh/ai/models")
    public CommonResult<GovAiModelVo> create(@RequestBody @Valid GovAiModelUpsertParam param) {
        return CommonResult.data(govAiModelService.create(param));
    }

    @Operation(summary = "更新模型")
    @CommonLog("更新AI模型")
    @PutMapping("/lh/ai/models/{id}")
    public CommonResult<GovAiModelVo> update(
            @PathVariable("id") String id,
            @RequestBody GovAiModelUpsertParam param) {
        return CommonResult.data(govAiModelService.update(id, param));
    }

    @Operation(summary = "轮换 API Key（写入 Vault，响应仅脱敏）")
    @CommonLog("轮换AI模型Key")
    @PostMapping("/lh/ai/models/{id}/rotate")
    public CommonResult<GovAiModelVo> rotate(
            @PathVariable("id") String id,
            @RequestBody @Valid GovAiModelRotateParam param) {
        return CommonResult.data(govAiModelService.rotate(id, param));
    }

    @Operation(summary = "测试连通性（LiteLLM + Vault Key）")
    @CommonLog("测试AI模型")
    @PostMapping("/lh/ai/models/{id}/test")
    public CommonResult<Map<String, Object>> test(@PathVariable("id") String id) {
        return CommonResult.data(govAiModelService.test(id));
    }

    @Operation(summary = "批量巡检（LiteLLM 健康 + 启用模型连通 + Key 过期预警）")
    @CommonLog("巡检AI模型")
    @PostMapping("/lh/ai/models/patrol")
    public CommonResult<Map<String, Object>> patrol(@RequestParam(required = false) String ws) {
        return CommonResult.data(govAiModelService.patrol(ws));
    }

    @Operation(summary = "启停模型")
    @CommonLog("启停AI模型")
    @PostMapping("/lh/ai/models/{id}/enable")
    public CommonResult<GovAiModelVo> enable(
            @PathVariable("id") String id,
            @RequestBody GovAiModelEnableParam param) {
        return CommonResult.data(govAiModelService.enable(id, param));
    }

    @Operation(summary = "路由策略列表")
    @GetMapping("/lh/ai/routes")
    public CommonResult<List<GovAiRouteVo>> routes(@RequestParam(required = false) String ws) {
        return CommonResult.data(govAiModelService.listRoutes(ws));
    }

    @Operation(summary = "保存路由策略")
    @CommonLog("保存AI路由")
    @PutMapping("/lh/ai/routes")
    public CommonResult<List<GovAiRouteVo>> saveRoutes(@RequestBody Object body) {
        // 前端 saveAiRoutes 提交 {"routes":[...]}；亦兼容裸数组 / 单对象
        List<GovAiRouteUpsertParam> list = new java.util.ArrayList<>();
        if (body instanceof List<?> raw) {
            for (Object o : raw) {
                list.add(cn.hutool.json.JSONUtil.toBean(cn.hutool.json.JSONUtil.parseObj(o), GovAiRouteUpsertParam.class));
            }
        } else {
            cn.hutool.json.JSONObject obj = cn.hutool.json.JSONUtil.parseObj(body);
            if (obj.containsKey("routes")) {
                cn.hutool.json.JSONArray arr = obj.getJSONArray("routes");
                if (arr != null) {
                    for (int i = 0; i < arr.size(); i++) {
                        list.add(arr.get(i, GovAiRouteUpsertParam.class));
                    }
                }
            } else {
                list.add(cn.hutool.json.JSONUtil.toBean(obj, GovAiRouteUpsertParam.class));
            }
        }
        return CommonResult.data(govAiModelService.saveRoutes(list));
    }

    @Operation(summary = "用量聚合")
    @GetMapping("/lh/ai/usage")
    public CommonResult<Map<String, Object>> usage(
            @RequestParam(required = false, defaultValue = "30d") String range,
            @RequestParam(required = false, defaultValue = "model") String group,
            @RequestParam(required = false) String ws) {
        return CommonResult.data(govAiModelService.usage(range, group, ws));
    }
}
