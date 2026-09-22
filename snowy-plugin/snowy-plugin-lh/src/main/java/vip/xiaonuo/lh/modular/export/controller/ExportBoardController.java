package vip.xiaonuo.lh.modular.export.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.xiaonuo.common.annotation.CommonLog;
import vip.xiaonuo.common.pojo.CommonResult;
import vip.xiaonuo.lh.modular.export.service.ExportBoardService;

import java.util.List;
import java.util.Map;

@Tag(name = "出湖与回流运营台")
@RestController
@Validated
public class ExportBoardController {

    @Resource
    private ExportBoardService exportBoardService;

    @Operation(summary = "出湖 KPI 汇总")
    @GetMapping("/lh/export/summary")
    public CommonResult<Map<String, Object>> summary(@RequestParam(required = false) String ws) {
        return CommonResult.data(exportBoardService.summary(ws));
    }

    @Operation(summary = "出湖作业/申请运营列表")
    @GetMapping("/lh/export/jobs")
    public CommonResult<List<Map<String, Object>>> jobs(
            @RequestParam(required = false) String ws,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q) {
        return CommonResult.data(exportBoardService.jobs(ws, status, q));
    }

    @Operation(summary = "出库审计（gov_export_audit 正式落库）")
    @GetMapping("/lh/export/audit")
    public CommonResult<Map<String, Object>> audit(
            @RequestParam(required = false) String ws,
            @RequestParam(required = false) String ticketNo) {
        return CommonResult.data(exportBoardService.audit(ws, ticketNo));
    }

    @Operation(summary = "到期停作业（扫描 expires_at 已过的 lake_export）")
    @CommonLog("出湖到期停作业")
    @PostMapping("/lh/export/expire-due")
    public CommonResult<Map<String, Object>> expireDue() {
        return CommonResult.data(exportBoardService.expireDue());
    }
}
