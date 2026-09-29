package vip.xiaonuo.lh.modular.sec.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.xiaonuo.common.annotation.CommonLog;
import vip.xiaonuo.common.pojo.CommonResult;
import vip.xiaonuo.lh.modular.sec.param.SecJobSaIdParam;
import vip.xiaonuo.lh.modular.sec.param.SecJobSaRegisterParam;
import vip.xiaonuo.lh.modular.sec.param.SecVaultRotateParam;
import vip.xiaonuo.lh.modular.sec.service.SecBoardService;

import java.util.Map;

@Tag(name = "安全中心运营台")
@RestController
@Validated
public class SecBoardController {

    @Resource
    private SecBoardService secBoardService;

    @Operation(summary = "安全中心 KPI")
    @GetMapping("/lh/sec/overview")
    public CommonResult<Map<String, Object>> overview(@RequestParam(required = false) String ws) {
        return CommonResult.data(secBoardService.overview(ws));
    }

    @Operation(summary = "授权策略分页")
    @GetMapping("/lh/sec/grants")
    public CommonResult<Map<String, Object>> grants(
            @RequestParam(required = false) String ws,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "1") long current,
            @RequestParam(required = false, defaultValue = "20") long size) {
        return CommonResult.data(secBoardService.pageGrants(ws, q, current, size));
    }

    @Operation(summary = "脱敏策略分页")
    @GetMapping("/lh/sec/masks")
    public CommonResult<Map<String, Object>> masks(
            @RequestParam(required = false) String ws,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "1") long current,
            @RequestParam(required = false, defaultValue = "20") long size) {
        return CommonResult.data(secBoardService.pageMasks(ws, q, current, size));
    }

    @Operation(summary = "分级分类资产分布")
    @GetMapping("/lh/sec/classification")
    public CommonResult<Map<String, Object>> classification(@RequestParam(required = false) String ws) {
        return CommonResult.data(secBoardService.classification(ws));
    }

    @Operation(summary = "高风险审计分页")
    @GetMapping("/lh/sec/audit")
    public CommonResult<Map<String, Object>> audit(
            @RequestParam(required = false) String ws,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "1") long current,
            @RequestParam(required = false, defaultValue = "20") long size) {
        return CommonResult.data(secBoardService.pageAudit(ws, q, current, size));
    }

    @Operation(summary = "作业 SA 列表（sec_job_sa，无明文）")
    @GetMapping("/lh/sec/sa")
    public CommonResult<Map<String, Object>> sa(@RequestParam(required = false) String ws) {
        return CommonResult.data(secBoardService.listSa(ws));
    }

    @Operation(summary = "注册作业 SA")
    @CommonLog("注册作业SA")
    @PostMapping("/lh/sec/sa")
    public CommonResult<Map<String, Object>> registerSa(@RequestBody @Valid SecJobSaRegisterParam param) {
        return CommonResult.data(secBoardService.registerSa(param));
    }

    @Operation(summary = "退役作业 SA")
    @CommonLog("退役作业SA")
    @PostMapping("/lh/sec/sa/retire")
    public CommonResult<String> retireSa(@RequestBody @Valid SecJobSaIdParam param) {
        secBoardService.retireSa(param.getId());
        return CommonResult.ok();
    }

    @Operation(summary = "Vault 健康/轮换台账（ig_secret_store，无明文）")
    @GetMapping("/lh/sec/vault/health")
    public CommonResult<Map<String, Object>> vaultHealth() {
        return CommonResult.data(secBoardService.vaultHealth());
    }

    @Operation(summary = "Vault 本地动态密轮换（数据源另置 binding stale）")
    @CommonLog("安全中心Vault轮换")
    @PostMapping("/lh/sec/vault/rotate")
    public CommonResult<Map<String, Object>> vaultRotate(@RequestBody @Valid SecVaultRotateParam param) {
        return CommonResult.data(secBoardService.rotateVault(param.getVaultPath()));
    }

    @Operation(summary = "查询路径 allow/forbid 示意")
    @GetMapping("/lh/sec/route-whitelist")
    public CommonResult<Map<String, Object>> routeWhitelist() {
        return CommonResult.data(secBoardService.routeWhitelist());
    }
}
