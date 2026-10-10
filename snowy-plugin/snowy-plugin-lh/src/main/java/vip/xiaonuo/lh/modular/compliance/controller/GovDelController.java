package vip.xiaonuo.lh.modular.compliance.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.xiaonuo.common.annotation.CommonLog;
import vip.xiaonuo.common.pojo.CommonResult;
import vip.xiaonuo.lh.modular.compliance.param.GovDelActionParam;
import vip.xiaonuo.lh.modular.compliance.param.GovDelBackfillGateParam;
import vip.xiaonuo.lh.modular.compliance.param.GovDelDekRegisterParam;
import vip.xiaonuo.lh.modular.compliance.param.GovDelEvidenceDownloadParam;
import vip.xiaonuo.lh.modular.compliance.param.GovDelExportGateParam;
import vip.xiaonuo.lh.modular.compliance.param.GovDelExportReceiptParam;
import vip.xiaonuo.lh.modular.compliance.param.GovDelHoldParam;
import vip.xiaonuo.lh.modular.compliance.param.GovDelIntakeParam;
import vip.xiaonuo.lh.modular.compliance.param.GovDelPlanEditParam;
import vip.xiaonuo.lh.modular.compliance.param.GovDelRequestCreateParam;
import vip.xiaonuo.lh.modular.compliance.param.GovDelRequestPageParam;
import vip.xiaonuo.lh.modular.compliance.param.GovDelRestrictParam;
import vip.xiaonuo.lh.modular.compliance.param.GovDelRevealParam;
import vip.xiaonuo.lh.modular.compliance.param.GovDelSubjectMapUpsertParam;
import vip.xiaonuo.lh.modular.compliance.param.GovDelSuppressionUpsertParam;
import vip.xiaonuo.lh.modular.compliance.result.GovDelRequestVo;
import vip.xiaonuo.lh.modular.compliance.result.GovDelSubjectMapVo;
import vip.xiaonuo.lh.modular.compliance.service.GovDelService;
import vip.xiaonuo.lh.modular.compliance.support.GovDelIntakeSignature;

import java.util.List;
import java.util.Map;

/**
 * 合规删除 / 被遗忘权（doc/合规删除.md）
 * <p>
 * 主路径：{@code /lh/compliance/*}；兼容：{@code /api/governance/compliance/*}
 */
@Tag(name = "合规删除 / 被遗忘权")
@RestController
@Validated
public class GovDelController {

    @Resource
    private GovDelService govDelService;

    @Operation(summary = "看板概览：状态分布 · SLA 风险 · 主体索引覆盖率")
    @GetMapping({"/lh/compliance/summary", "/api/governance/compliance/summary"})
    public CommonResult<Map<String, Object>> summary(@RequestParam(required = false) String ws) {
        return CommonResult.data(govDelService.summary(ws));
    }

    @Operation(summary = "请求分页")
    @GetMapping({"/lh/compliance/requests", "/api/governance/compliance/requests"})
    public CommonResult<Page<GovDelRequestVo>> requests(GovDelRequestPageParam param) {
        return CommonResult.data(govDelService.pageRequests(param));
    }

    @Operation(summary = "请求详情：计划 + 时间线")
    @GetMapping({"/lh/compliance/request", "/api/governance/compliance/request"})
    public CommonResult<GovDelRequestVo> detail(@RequestParam("reqId") String reqId) {
        return CommonResult.data(govDelService.detail(reqId));
    }

    @Operation(summary = "受理请求（主体 ID 即时 HMAC，明文进 Vault，不落库）")
    @CommonLog("合规删除受理")
    @PostMapping({"/lh/compliance/requests", "/api/governance/compliance/requests"})
    public CommonResult<GovDelRequestVo> create(@RequestBody @Valid GovDelRequestCreateParam param) {
        return CommonResult.data(govDelService.create(param));
    }

    @Operation(summary = "二次授权查看主体 ID 明文（回填请求号 + 用途；写审计）")
    @CommonLog("合规删除查看主体明文")
    @PostMapping({"/lh/compliance/subject-plain", "/api/governance/compliance/subject-plain"})
    public CommonResult<Map<String, Object>> revealSubjectPlain(@RequestBody @Valid GovDelRevealParam param) {
        return CommonResult.data(govDelService.revealSubjectPlain(param));
    }

    @Operation(summary = "评估：展开主体索引生成删除计划")
    @CommonLog("合规删除评估")
    @PostMapping({"/lh/compliance/assess", "/api/governance/compliance/assess"})
    public CommonResult<GovDelRequestVo> assess(@RequestBody @Valid GovDelActionParam param) {
        return CommonResult.data(govDelService.assess(param));
    }

    @Operation(summary = "计划编辑：补充 / 排除载体")
    @CommonLog("合规删除计划编辑")
    @PutMapping({"/lh/compliance/plan", "/api/governance/compliance/plan"})
    public CommonResult<GovDelRequestVo> editPlan(@RequestBody @Valid GovDelPlanEditParam param) {
        return CommonResult.data(govDelService.editPlan(param));
    }

    @Operation(summary = "试算：命中行与影响面（不删数据）")
    @CommonLog("合规删除试算")
    @PostMapping({"/lh/compliance/dry-run", "/api/governance/compliance/dry-run"})
    public CommonResult<Map<String, Object>> dryRun(@RequestBody @Valid GovDelActionParam param) {
        return CommonResult.data(govDelService.dryRun(param));
    }

    @Operation(summary = "提交审批：写 apply_ticket(compliance_delete)")
    @CommonLog("合规删除提交审批")
    @PostMapping({"/lh/compliance/submit", "/api/governance/compliance/submit"})
    public CommonResult<GovDelRequestVo> submit(@RequestBody @Valid GovDelActionParam param) {
        return CommonResult.data(govDelService.submit(param));
    }

    @Operation(summary = "排期执行窗口（须审批通过）")
    @CommonLog("合规删除排期")
    @PostMapping({"/lh/compliance/schedule", "/api/governance/compliance/schedule"})
    public CommonResult<GovDelRequestVo> schedule(@RequestBody @Valid GovDelActionParam param) {
        return CommonResult.data(govDelService.schedule(param));
    }

    @Operation(summary = "执行：按载体顺序硬删（须回填请求号确认）")
    @CommonLog("合规删除执行")
    @PostMapping({"/lh/compliance/execute", "/api/governance/compliance/execute"})
    public CommonResult<GovDelRequestVo> execute(@RequestBody @Valid GovDelActionParam param) {
        return CommonResult.data(govDelService.execute(param));
    }

    @Operation(summary = "验证：残留反查 + 时间旅行不可读")
    @CommonLog("合规删除验证")
    @PostMapping({"/lh/compliance/verify", "/api/governance/compliance/verify"})
    public CommonResult<GovDelRequestVo> verify(@RequestBody @Valid GovDelActionParam param) {
        return CommonResult.data(govDelService.verify(param));
    }

    @Operation(summary = "限制处理（个保法 §47 兜底）")
    @CommonLog("合规删除限制处理")
    @PostMapping({"/lh/compliance/restrict", "/api/governance/compliance/restrict"})
    public CommonResult<GovDelRequestVo> restrict(@RequestBody @Valid GovDelRestrictParam param) {
        return CommonResult.data(govDelService.restrict(param));
    }

    @Operation(summary = "法务冻结")
    @CommonLog("合规删除冻结")
    @PostMapping({"/lh/compliance/hold", "/api/governance/compliance/hold"})
    public CommonResult<GovDelRequestVo> hold(@RequestBody @Valid GovDelHoldParam param) {
        return CommonResult.data(govDelService.hold(param));
    }

    @Operation(summary = "解除冻结")
    @CommonLog("合规删除解除冻结")
    @PostMapping({"/lh/compliance/hold/release", "/api/governance/compliance/hold/release"})
    public CommonResult<GovDelRequestVo> releaseHold(@RequestBody @Valid GovDelHoldParam param) {
        return CommonResult.data(govDelService.releaseHold(param));
    }

    @Operation(summary = "中止请求")
    @CommonLog("合规删除中止")
    @PostMapping({"/lh/compliance/abort", "/api/governance/compliance/abort"})
    public CommonResult<GovDelRequestVo> abort(@RequestBody @Valid GovDelActionParam param) {
        return CommonResult.data(govDelService.abort(param));
    }

    @Operation(summary = "证据包：清单 + 落对象存储（WORM）+ sha256")
    @CommonLog("合规删除生成证据包")
    @GetMapping({"/lh/compliance/evidence", "/api/governance/compliance/evidence"})
    public CommonResult<Map<String, Object>> evidence(@RequestParam("reqId") String reqId) {
        return CommonResult.data(govDelService.evidence(reqId));
    }

    @Operation(summary = "二次授权下载证据包 ZIP（回填请求号 + 用途；写审计）")
    @CommonLog("合规删除下载证据包")
    @PostMapping({"/lh/compliance/evidence/download", "/api/governance/compliance/evidence/download"})
    public CommonResult<Map<String, Object>> downloadEvidence(@RequestBody @Valid GovDelEvidenceDownloadParam param) {
        return CommonResult.data(govDelService.downloadEvidence(param));
    }

    @Operation(summary = "主体索引列表")
    @GetMapping({"/lh/compliance/subject-maps", "/api/governance/compliance/subject-maps"})
    public CommonResult<List<GovDelSubjectMapVo>> subjectMaps(
            @RequestParam(required = false) String ws,
            @RequestParam(required = false) String subjectType,
            @RequestParam(required = false) String carrier) {
        return CommonResult.data(govDelService.listSubjectMaps(ws, subjectType, carrier));
    }

    @Operation(summary = "主体索引登记 / 更新")
    @CommonLog("主体索引维护")
    @PutMapping({"/lh/compliance/subject-maps", "/api/governance/compliance/subject-maps"})
    public CommonResult<GovDelSubjectMapVo> upsertSubjectMap(@RequestBody @Valid GovDelSubjectMapUpsertParam param) {
        return CommonResult.data(govDelService.upsertSubjectMap(param));
    }

    @Operation(summary = "主体索引覆盖率与缺口")
    @GetMapping({"/lh/compliance/coverage", "/api/governance/compliance/coverage"})
    public CommonResult<Map<String, Object>> coverage(@RequestParam(required = false) String ws) {
        return CommonResult.data(govDelService.coverage(ws));
    }

    @Operation(summary = "E7 补数门禁预检：表×分区是否命中已删分区")
    @PostMapping({"/lh/compliance/gate/backfill-check", "/api/governance/compliance/gate/backfill-check"})
    public CommonResult<Map<String, Object>> backfillGateCheck(@RequestBody @Valid GovDelBackfillGateParam param) {
        return CommonResult.data(govDelService.backfillGateCheck(param));
    }

    @Operation(summary = "E7 出湖门禁预检：源表是否命中 restricted")
    @PostMapping({"/lh/compliance/gate/export-check", "/api/governance/compliance/gate/export-check"})
    public CommonResult<Map<String, Object>> exportGateCheck(@RequestBody @Valid GovDelExportGateParam param) {
        return CommonResult.data(govDelService.exportGateCheck(param));
    }

    @Operation(summary = "SLA 黄/红扫描并推夜莺（剩余≤1/3→P1；超期→P0）")
    @CommonLog("合规 SLA 告警扫描")
    @PostMapping({"/lh/compliance/sla/scan", "/api/governance/compliance/sla/scan"})
    public CommonResult<Map<String, Object>> scanSla(@RequestParam(required = false) String ws) {
        return CommonResult.data(govDelService.scanSlaAlerts(ws));
    }

    @Operation(summary = "抑制名单列表（ETL/CDC 拉取；仅 subject_id_hash）")
    @GetMapping({"/lh/compliance/suppression", "/api/governance/compliance/suppression"})
    public CommonResult<List<Map<String, Object>>> listSuppressions(
            @RequestParam(required = false) String ws,
            @RequestParam(required = false) String subjectIdHash,
            @RequestParam(required = false) String objectFqn,
            @RequestParam(required = false, defaultValue = "true") Boolean activeOnly) {
        return CommonResult.data(govDelService.listSuppressions(ws, subjectIdHash, objectFqn, activeOnly));
    }

    @Operation(summary = "抑制名单登记 / 更新（防复活）")
    @CommonLog("合规抑制名单维护")
    @PostMapping({"/lh/compliance/suppression", "/api/governance/compliance/suppression"})
    public CommonResult<Map<String, Object>> upsertSuppression(@RequestBody GovDelSuppressionUpsertParam param) {
        return CommonResult.data(govDelService.upsertSuppression(param));
    }

    @Operation(summary = "出湖副本删除回执 / 书面残留声明")
    @CommonLog("合规出湖回执登记")
    @PostMapping({"/lh/compliance/export/receipt", "/api/governance/compliance/export/receipt"})
    public CommonResult<GovDelRequestVo> registerExportReceipt(@RequestBody @Valid GovDelExportReceiptParam param) {
        return CommonResult.data(govDelService.registerExportReceipt(param));
    }

    @Operation(summary = "J3 外部 DSR webhook 送单（签名校验；sourceSystem+sourceRef 幂等）")
    @CommonLog("合规删除外部送单")
    @PostMapping({"/lh/compliance/intake", "/api/governance/compliance/intake"})
    public CommonResult<Map<String, Object>> intake(
            @RequestBody @Valid GovDelIntakeParam param,
            @RequestHeader(value = GovDelIntakeSignature.HEADER, required = false) String signature,
            @RequestHeader(value = GovDelIntakeSignature.HEADER_TS, required = false) String timestamp) {
        return CommonResult.data(govDelService.intake(param, signature, timestamp));
    }

    @Operation(summary = "J3 登记每主体 PII 列 DEK（crypto-shredding 前置；密钥进 Vault）")
    @CommonLog("合规删除登记 DEK")
    @PutMapping({"/lh/compliance/crypto/deks", "/api/governance/compliance/crypto/deks"})
    public CommonResult<Map<String, Object>> registerDek(@RequestBody @Valid GovDelDekRegisterParam param) {
        return CommonResult.data(govDelService.registerDek(param));
    }

    @Operation(summary = "J3 DEK 登记列表（无密钥材料）")
    @GetMapping({"/lh/compliance/crypto/deks", "/api/governance/compliance/crypto/deks"})
    public CommonResult<List<Map<String, Object>>> listDeks(
            @RequestParam(required = false) String ws,
            @RequestParam(required = false) String subjectIdHash,
            @RequestParam(required = false) String status) {
        return CommonResult.data(govDelService.listDeks(ws, subjectIdHash, status));
    }
}
