package vip.xiaonuo.lh.modular.apply.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import vip.xiaonuo.common.annotation.CommonLog;
import vip.xiaonuo.common.pojo.CommonResult;
import vip.xiaonuo.lh.modular.apply.entity.ApplyTicket;
import vip.xiaonuo.lh.modular.apply.param.ApplyTicketCreateParam;
import vip.xiaonuo.lh.modular.apply.param.ApplyTicketDecideParam;
import vip.xiaonuo.lh.modular.apply.param.ApplyTicketPageParam;
import vip.xiaonuo.lh.modular.apply.service.ApplyTicketService;

import java.util.Map;

@Tag(name = "申请中心")
@RestController
@Validated
public class ApplyTicketController {

    @Resource
    private ApplyTicketService applyTicketService;

    @Operation(summary = "提交申请")
    @CommonLog("提交申请单")
    @PostMapping("/lh/apply/tickets")
    public CommonResult<ApplyTicket> create(@RequestBody @Valid ApplyTicketCreateParam param) {
        return CommonResult.data(applyTicketService.create(param));
    }

    @Operation(summary = "我的申请")
    @GetMapping("/lh/apply/tickets")
    public CommonResult<Page<ApplyTicket>> pageMine(ApplyTicketPageParam param) {
        return CommonResult.data(applyTicketService.pageMine(param));
    }

    @Operation(summary = "待我审批（超管 / 资产 Owner / 空间 Owner）")
    @GetMapping("/lh/apply/tickets/pending")
    public CommonResult<Page<ApplyTicket>> pagePending(ApplyTicketPageParam param) {
        return CommonResult.data(applyTicketService.pagePending(param));
    }

    @Operation(summary = "看板 KPI（待我/我的/本月通过驳回）")
    @GetMapping("/lh/apply/kpi")
    public CommonResult<Map<String, Object>> kpi(
            @RequestParam(required = false) String ws) {
        return CommonResult.data(applyTicketService.kpi(ws));
    }

    @Operation(summary = "通过申请")
    @CommonLog("通过申请单")
    @PostMapping("/lh/apply/tickets/approve")
    public CommonResult<Map<String, Object>> approve(@RequestBody @Valid ApplyTicketDecideParam param) {
        return CommonResult.data(applyTicketService.approve(param));
    }

    @Operation(summary = "驳回申请")
    @CommonLog("驳回申请单")
    @PostMapping("/lh/apply/tickets/reject")
    public CommonResult<ApplyTicket> reject(@RequestBody @Valid ApplyTicketDecideParam param) {
        return CommonResult.data(applyTicketService.reject(param));
    }
}
