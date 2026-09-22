package vip.xiaonuo.lh.modular.compliance.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.xiaonuo.auth.core.pojo.SaBaseLoginUser;
import vip.xiaonuo.auth.core.util.StpLoginUserUtil;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.modular.apply.entity.ApplyTicket;
import vip.xiaonuo.lh.modular.apply.param.ApplyTicketCreateParam;
import vip.xiaonuo.lh.modular.apply.service.ApplyTicketService;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetMapper;
import vip.xiaonuo.lh.modular.compliance.entity.GovDelEvidence;
import vip.xiaonuo.lh.modular.compliance.entity.GovDelExec;
import vip.xiaonuo.lh.modular.compliance.entity.GovDelHold;
import vip.xiaonuo.lh.modular.compliance.entity.GovDelRequest;
import vip.xiaonuo.lh.modular.compliance.entity.GovDelSubjectMap;
import vip.xiaonuo.lh.modular.compliance.entity.GovDelTarget;
import vip.xiaonuo.lh.modular.compliance.mapper.GovDelEvidenceMapper;
import vip.xiaonuo.lh.modular.compliance.mapper.GovDelExecMapper;
import vip.xiaonuo.lh.modular.compliance.mapper.GovDelHoldMapper;
import vip.xiaonuo.lh.modular.compliance.mapper.GovDelRequestMapper;
import vip.xiaonuo.lh.modular.compliance.mapper.GovDelSubjectMapMapper;
import vip.xiaonuo.lh.modular.compliance.mapper.GovDelTargetMapper;
import vip.xiaonuo.lh.modular.compliance.param.GovDelActionParam;
import vip.xiaonuo.lh.modular.compliance.param.GovDelHoldParam;
import vip.xiaonuo.lh.modular.compliance.param.GovDelPlanEditParam;
import vip.xiaonuo.lh.modular.compliance.param.GovDelRequestCreateParam;
import vip.xiaonuo.lh.modular.compliance.param.GovDelRequestPageParam;
import vip.xiaonuo.lh.modular.compliance.param.GovDelRestrictParam;
import vip.xiaonuo.lh.modular.compliance.param.GovDelRevealParam;
import vip.xiaonuo.lh.modular.compliance.param.GovDelSubjectMapUpsertParam;
import vip.xiaonuo.lh.modular.compliance.result.GovDelRequestVo;
import vip.xiaonuo.lh.modular.compliance.result.GovDelSubjectMapVo;
import vip.xiaonuo.lh.modular.compliance.result.GovDelTargetVo;
import vip.xiaonuo.lh.modular.compliance.service.GovDelService;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.engine.ClickHouseClient;
import vip.xiaonuo.lh.core.engine.TrinoClient;
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.core.vault.LhVaultPaths;
import vip.xiaonuo.lh.modular.compliance.support.GovDelCkSql;
import vip.xiaonuo.lh.modular.compliance.support.GovDelIcebergSql;
import vip.xiaonuo.lh.modular.lifecycle.param.GovLcTableActionParam;
import vip.xiaonuo.lh.modular.lifecycle.result.GovLcRunVo;
import vip.xiaonuo.lh.modular.lifecycle.service.GovLcService;
import vip.xiaonuo.lh.modular.lifecycle.support.GovLcMetadataSql;
import vip.xiaonuo.lh.modular.lineage.service.GovLineageService;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 合规删除 P0：请求/计划/执行流水/证据落库。
 * Iceberg 硬删与 dry-run COUNT 走 Trino；CK dry-run COUNT 走 HTTP；
 * 合并与定向快照过期通过 {@link GovLcService} 复用生命周期执行面。
 */
@Service
public class GovDelServiceImpl implements GovDelService {

    private static final String WS_DEFAULT = "default";
    private static final String NOT_DELETE = "NOT_DELETE";
    private static final int SLA_WORK_DAYS = 15;
    private static final int BACKUP_OBSERVE_DAYS = 30;
    private static final int RESTRICT_REVIEW_DAYS = 90;

    private static final Map<String, Integer> CARRIER_ORDER = Map.ofEntries(
            Map.entry("source", 10),
            Map.entry("iceberg", 20),
            Map.entry("ck", 30),
            Map.entry("sink", 40),
            Map.entry("export", 50),
            Map.entry("platform", 60),
            Map.entry("ai", 60),
            Map.entry("meta", 70),
            Map.entry("log", 80),
            Map.entry("backup", 90),
            Map.entry("kafka", 95)
    );

    private static final Map<String, String> CARRIER_LABEL = Map.ofEntries(
            Map.entry("source", "源库"),
            Map.entry("iceberg", "湖表 Iceberg"),
            Map.entry("ck", "ClickHouse"),
            Map.entry("sink", "回流副本"),
            Map.entry("export", "出湖副本"),
            Map.entry("platform", "平台留存"),
            Map.entry("ai", "AI / 知识库"),
            Map.entry("meta", "元数据样例"),
            Map.entry("log", "日志"),
            Map.entry("backup", "备份 / 冷归档"),
            Map.entry("kafka", "消息队列")
    );

    private static final Map<String, String> MODE_LABEL = Map.ofEntries(
            Map.entry("cow", "Copy-on-Write DELETE"),
            Map.entry("mor", "Merge-on-Read DELETE + 合并"),
            Map.entry("drop_partition", "分区删除 / 重导"),
            Map.entry("ck_mutation", "ALTER DELETE（mutation）"),
            Map.entry("sink_delete", "下游按主键删除"),
            Map.entry("notify", "发删除请求并取回执"),
            Map.entry("purge", "物理清除"),
            Map.entry("register", "登记到期销毁"),
            Map.entry("retention", "保留期到期自然消亡"),
            Map.entry("manual", "人工处理")
    );

    private static final Map<String, String> STATUS_LABEL = Map.ofEntries(
            Map.entry("assessing", "评估中"),
            Map.entry("pending_approval", "待审批"),
            Map.entry("scheduled", "已排期"),
            Map.entry("executing", "执行中"),
            Map.entry("verifying", "验证中"),
            Map.entry("partial_failed", "部分失败"),
            Map.entry("done", "已完成"),
            Map.entry("archived", "待备份销毁"),
            Map.entry("destroyed", "已销毁"),
            Map.entry("restricted", "限制处理"),
            Map.entry("on_hold", "法务冻结"),
            Map.entry("rejected", "已驳回")
    );

    private static final Map<String, String> REQ_TYPE_LABEL = Map.of(
            "forget", "被遗忘权",
            "erase_error", "错误数据擦除",
            "regulator", "监管责令",
            "contract_expire", "合同到期",
            "account_close", "账号注销"
    );

    /** HMAC key 缓存（启动后首次 resolve 填充；轮换需重启或后续加 refresh）。 */
    private volatile String resolvedHmacKey;

    @Resource
    private GovDelRequestMapper requestMapper;
    @Resource
    private GovDelTargetMapper targetMapper;
    @Resource
    private GovDelExecMapper execMapper;
    @Resource
    private GovDelEvidenceMapper evidenceMapper;
    @Resource
    private GovDelHoldMapper holdMapper;
    @Resource
    private GovDelSubjectMapMapper subjectMapMapper;
    @Resource
    private GovAssetMapper govAssetMapper;
    @Resource
    private ApplyTicketService applyTicketService;
    @Resource
    private GovLcService govLcService;
    @Resource
    private GovLineageService govLineageService;
    @Resource
    private TrinoClient trinoClient;
    @Resource
    private ClickHouseClient clickHouseClient;
    @Resource
    private LhProperties lhProperties;
    @Resource
    private LhVaultClient vaultClient;

    // ───────────────────────────── 看板 ─────────────────────────────

    @Override
    public Map<String, Object> summary(String ws) {
        String workspace = wsOrDefault(ws);
        List<GovDelRequest> all = requestMapper.selectList(new QueryWrapper<GovDelRequest>().lambda()
                .eq(GovDelRequest::getWs, workspace)
                .eq(GovDelRequest::getDeleteFlag, NOT_DELETE));
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (GovDelRequest r : all) {
            byStatus.merge(r.getStatus(), 1L, Long::sum);
        }
        Date now = new Date();
        long overdue = all.stream().filter(r -> isOpen(r.getStatus())
                && r.getDeadline() != null && r.getDeadline().before(now)).count();
        long dueSoon = all.stream().filter(r -> isOpen(r.getStatus())
                && r.getDeadline() != null && !r.getDeadline().before(now)
                && daysBetween(now, r.getDeadline()) <= 3).count();
        long restricted = all.stream().filter(r -> "restricted".equals(r.getStatus())).count();
        long pendingDestroy = all.stream().filter(r -> r.getDestroyAfter() != null
                && !"destroyed".equals(r.getStatus())).count();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ws", workspace);
        out.put("total", all.size());
        out.put("byStatus", byStatus);
        out.put("open", all.stream().filter(r -> isOpen(r.getStatus())).count());
        out.put("pendingApproval", byStatus.getOrDefault("pending_approval", 0L));
        out.put("executing", byStatus.getOrDefault("executing", 0L) + byStatus.getOrDefault("scheduled", 0L));
        out.put("overdue", overdue);
        out.put("dueSoon", dueSoon);
        out.put("restricted", restricted);
        out.put("pendingDestroy", pendingDestroy);
        out.put("coverage", coverage(workspace));
        out.put("sla", Map.of(
                "ackHours", 24,
                "execWorkDays", SLA_WORK_DAYS,
                "backupObserveDays", BACKUP_OBSERVE_DAYS,
                "restrictReviewDays", RESTRICT_REVIEW_DAYS
        ));
        return out;
    }

    @Override
    public Page<GovDelRequestVo> pageRequests(GovDelRequestPageParam param) {
        String workspace = wsOrDefault(param.getWs());
        long current = param.getCurrent() == null ? 1L : param.getCurrent();
        long size = param.getSize() == null ? 20L : param.getSize();
        QueryWrapper<GovDelRequest> qw = new QueryWrapper<GovDelRequest>().checkSqlInjection();
        qw.lambda().eq(GovDelRequest::getWs, workspace)
                .eq(GovDelRequest::getDeleteFlag, NOT_DELETE)
                .eq(StrUtil.isNotBlank(param.getStatus()), GovDelRequest::getStatus, StrUtil.trim(param.getStatus()))
                .eq(StrUtil.isNotBlank(param.getReqType()), GovDelRequest::getReqType, StrUtil.trim(param.getReqType()));
        if (StrUtil.isNotBlank(param.getKw())) {
            String kw = StrUtil.trim(param.getKw());
            qw.lambda().and(w -> w.like(GovDelRequest::getReqNo, kw)
                    .or().like(GovDelRequest::getSubjectMasked, kw)
                    .or().like(GovDelRequest::getSourceRef, kw));
        }
        qw.lambda().orderByDesc(GovDelRequest::getCreateTime);
        Page<GovDelRequest> raw = requestMapper.selectPage(new Page<>(current, size), qw);

        List<String> reqIds = raw.getRecords().stream().map(GovDelRequest::getId).toList();
        Map<String, List<GovDelTarget>> targetsByReq = loadTargets(reqIds);
        Page<GovDelRequestVo> out = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        out.setRecords(raw.getRecords().stream()
                .map(r -> toVo(r, targetsByReq.getOrDefault(r.getId(), List.of()), false))
                .toList());
        return out;
    }

    @Override
    public GovDelRequestVo detail(String reqIdOrNo) {
        GovDelRequest req = requireRequest(reqIdOrNo);
        GovDelRequestVo vo = toVo(req, listTargets(req.getId()), true);
        vo.setTimeline(timeline(req.getId()));
        return vo;
    }

    // ───────────────────────────── 受理与计划 ─────────────────────────────

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovDelRequestVo create(GovDelRequestCreateParam param) {
        String workspace = wsOrDefault(param.getWs());
        String subjectType = StrUtil.blankToDefault(param.getSubjectType(), "user").trim().toLowerCase(Locale.ROOT);
        String plain = StrUtil.trim(param.getSubjectId());
        Date now = new Date();

        GovDelRequest req = new GovDelRequest();
        req.setId(IdUtil.getSnowflakeNextIdStr());
        req.setRevision(1);
        req.setStatus("assessing");
        req.setWs(workspace);
        req.setReqNo(nextReqNo());
        req.setSubjectType(subjectType);
        req.setSubjectIdHash(hashSubject(subjectType, plain));
        req.setSubjectMasked(mask(plain));
        String vaultPath = LhVaultPaths.complianceSubject(req.getReqNo());
        req.setVaultPath(vaultPath);
        req.setReqType(StrUtil.blankToDefault(param.getReqType(), "forget").trim().toLowerCase(Locale.ROOT));
        req.setLegalBasis(param.getLegalBasis());
        req.setScopeLabel(StrUtil.blankToDefault(param.getScopeLabel(), "指定行"));
        req.setSourceSystem(param.getSourceSystem());
        req.setSourceRef(param.getSourceRef());
        req.setDeadline(param.getDeadline() != null ? param.getDeadline() : plusWorkDays(now, SLA_WORK_DAYS));
        req.setApplicant(currentOperator());
        req.setRemark(param.getRemark());
        req.setDeleteFlag(NOT_DELETE);
        req.setCreateTime(now);
        req.setUpdateTime(now);

        // 明文仅进 Vault；门户表只留 hash + vault_path
        Map<String, Object> secret = new LinkedHashMap<>();
        secret.put("subjectId", plain);
        secret.put("subjectType", subjectType);
        secret.put("reqNo", req.getReqNo());
        vaultClient.write(vaultPath, secret);

        requestMapper.insert(req);

        logExec(req, null, "request.create", "success", null, null,
                "受理 " + reqTypeLabel(req.getReqType()) + "；SLA " + SLA_WORK_DAYS + " 工作日；明文已入 Vault");

        if (param.getAutoAssess() == null || Boolean.TRUE.equals(param.getAutoAssess())) {
            buildPlan(req);
        }
        return detail(req.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> revealSubjectPlain(GovDelRevealParam param) {
        GovDelRequest req = requireRequest(param.getReqId());
        String confirm = StrUtil.trim(param.getConfirmReqNo());
        if (!req.getReqNo().equals(confirm)) {
            throw new CommonException("二次确认失败：请回填正确的请求号 {}", req.getReqNo());
        }
        String reason = StrUtil.trim(param.getReason());
        if (StrUtil.isBlank(reason) || reason.length() < 4) {
            throw new CommonException("请填写查看用途（至少 4 字），将写入审计");
        }
        String vaultPath = StrUtil.blankToDefault(req.getVaultPath(),
                LhVaultPaths.complianceSubject(req.getReqNo()));
        if (!vaultClient.exists(vaultPath)) {
            throw new CommonException("Vault 无主体明文（演示种子或已销毁）：{}", vaultPath);
        }
        String plain = vaultClient.getString(vaultPath, "subjectId");
        if (StrUtil.isBlank(plain)) {
            throw new CommonException("Vault 条目缺少 subjectId：{}", vaultPath);
        }

        // 审计流水：禁止写入明文
        logExec(req, null, "subject.reveal", "success", null, null,
                "二次授权查看明文 · 用途：" + StrUtil.maxLength(reason, 200)
                        + " · 操作人：" + currentOperator());
        addEvidence(req, "audit", "主体明文二次授权",
                JSONUtil.toJsonStr(Map.of(
                        "action", "subject.reveal",
                        "reqNo", req.getReqNo(),
                        "operator", currentOperator(),
                        "reason", reason,
                        "vaultPath", vaultPath,
                        "at", new Date()
                )));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reqId", req.getId());
        out.put("reqNo", req.getReqNo());
        out.put("subjectType", req.getSubjectType());
        out.put("subjectMasked", req.getSubjectMasked());
        out.put("subjectId", plain);
        out.put("vaultPath", vaultPath);
        out.put("reason", reason);
        out.put("operator", currentOperator());
        out.put("revealedAt", new Date());
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovDelRequestVo assess(GovDelActionParam param) {
        GovDelRequest req = requireRequest(param.getReqId());
        assertMutable(req);
        buildPlan(req);
        return detail(req.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovDelRequestVo editPlan(GovDelPlanEditParam param) {
        GovDelRequest req = requireRequest(param.getReqId());
        assertMutable(req);
        Date now = new Date();
        int added = 0;

        if (param.getAdd() != null) {
            for (GovDelPlanEditParam.TargetItem item : param.getAdd()) {
                if (StrUtil.isBlank(item.getObjectFqn())) {
                    throw new CommonException("补充载体须填写 objectFqn");
                }
                String carrier = StrUtil.blankToDefault(item.getCarrier(), "iceberg").trim().toLowerCase(Locale.ROOT);
                GovDelTarget t = newTarget(req, carrier, item.getObjectFqn().trim(),
                        item.getScopeExpr(), StrUtil.blankToDefault(item.getMode(), defaultMode(carrier)), null);
                t.setRowsEst(item.getRowsEst() != null ? item.getRowsEst() : 0L);
                t.setManualAdded(true);
                targetMapper.insert(t);
                added++;
            }
        }

        int excluded = 0;
        if (param.getExcludeIds() != null && !param.getExcludeIds().isEmpty()) {
            if (StrUtil.isBlank(param.getExcludeReason())) {
                throw new CommonException("排除载体必须填写理由（将进入限制处理复查）");
            }
            for (String id : param.getExcludeIds()) {
                GovDelTarget t = targetMapper.selectById(id);
                if (t == null || !req.getId().equals(t.getReqId())) {
                    continue;
                }
                if ("done".equals(t.getStatus())) {
                    throw new CommonException("已执行的载体不可排除：" + t.getObjectFqn());
                }
                t.setStatus("excluded");
                t.setExcludeReason(param.getExcludeReason());
                t.setReviewAt(plusDays(now, RESTRICT_REVIEW_DAYS));
                t.setUpdateTime(now);
                targetMapper.updateById(t);
                excluded++;
            }
        }

        int confirmed = 0;
        if (param.getConfirmIds() != null && !param.getConfirmIds().isEmpty()) {
            for (String id : param.getConfirmIds()) {
                GovDelTarget t = targetMapper.selectById(id);
                if (t == null || !req.getId().equals(t.getReqId())) {
                    continue;
                }
                if (!"inferred".equalsIgnoreCase(StrUtil.blankToDefault(t.getLineageConfidence(), ""))) {
                    continue;
                }
                t.setLineageConfirmed(true);
                t.setUpdateTime(now);
                targetMapper.updateById(t);
                confirmed++;
            }
        }

        logExec(req, null, "plan.edit", "success", null, null,
                "补充 " + added + " 项，排除 " + excluded + " 项，确认推断血缘 " + confirmed + " 项");
        return detail(req.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> dryRun(GovDelActionParam param) {
        GovDelRequest req = requireRequest(param.getReqId());
        List<GovDelTarget> targets = listTargets(req.getId());
        if (targets.isEmpty()) {
            throw new CommonException("计划为空，请先评估");
        }
        Date now = new Date();
        long rows = 0;
        int engineOk = 0;
        int stubOk = 0;
        int engineFail = 0;
        List<Map<String, Object>> rowsByCarrier = new ArrayList<>();
        for (GovDelTarget t : targets) {
            if ("excluded".equals(t.getStatus())) {
                continue;
            }
            CountHit hit = countHit(req, t);
            t.setRowsEst(hit.rows());
            t.setUpdateTime(now);
            targetMapper.updateById(t);
            rows += hit.rows();
            if (hit.degraded()) {
                engineFail++;
                stubOk++;
            } else if ("stub".equals(hit.source())) {
                stubOk++;
            } else {
                engineOk++;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("targetId", t.getId());
            m.put("carrier", t.getCarrier());
            m.put("objectFqn", t.getObjectFqn());
            m.put("mode", t.getMode());
            m.put("rowsEst", hit.rows());
            m.put("source", hit.source());
            m.put("degraded", hit.degraded());
            if (StrUtil.isNotBlank(hit.message())) {
                m.put("message", hit.message());
            }
            rowsByCarrier.add(m);
        }
        boolean estimated = stubOk > 0 || engineFail > 0;
        String source = "trino=" + engineOk + " · stub=" + stubOk
                + (engineFail > 0 ? " · engineFail=" + engineFail : "");
        logExec(req, null, "plan.dry-run", engineFail > 0 ? "warn" : "success", param.getExecKey(), null,
                "试算命中 " + rows + " 行 / " + rowsByCarrier.size() + " 个载体（" + source + "）");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reqNo", req.getReqNo());
        out.put("rowsEstTotal", rows);
        out.put("targets", rowsByCarrier);
        out.put("snapshotNotice", "执行将对涉及的 Iceberg 表定向 expire_snapshots(retain_last=1)，这些表短期失去时间旅行回滚窗口");
        out.put("estimated", estimated);
        out.put("engineCounted", engineOk);
        out.put("stubCounted", stubOk);
        out.put("source", source);
        return out;
    }

    /**
     * Iceberg → Trino COUNT；CK → ClickHouse HTTP COUNT；其余载体仍用可复现占位。
     * 引擎不可达时 soft-fail 回退占位，不阻断试算。
     */
    private CountHit countHit(GovDelRequest req, GovDelTarget t) {
        String carrier = StrUtil.blankToDefault(t.getCarrier(), "");
        if ("iceberg".equals(carrier)) {
            long n = countRemaining(req, t);
            if (n >= 0) {
                return new CountHit(n, "trino", false, null);
            }
            long stub = estimateRows(req, t);
            return new CountHit(stub, "stub", true, "Trino COUNT 失败，已回退占位");
        }
        if ("ck".equals(carrier)) {
            long n = countClickHouse(req, t);
            if (n >= 0) {
                return new CountHit(n, "ck", false, null);
            }
            long stub = estimateRows(req, t);
            return new CountHit(stub, "stub", true, "ClickHouse COUNT 失败，已回退占位");
        }
        return new CountHit(estimateRows(req, t), "stub", false, null);
    }

    private long countClickHouse(GovDelRequest req, GovDelTarget t) {
        try {
            String column = subjectColumn(t);
            if (StrUtil.isBlank(column)) {
                return -1L;
            }
            GovLcMetadataSql.TableRef ref = GovDelCkSql.parse(t.getObjectFqn());
            String sql = GovDelCkSql.count(ref.schema(), ref.table(), column, req.getSubjectIdHash());
            Map<String, Object> exec = clickHouseClient.query(sql);
            if (Boolean.TRUE.equals(exec.get("degraded"))) {
                return -1L;
            }
            return parseCnt(exec);
        } catch (Exception e) {
            return -1L;
        }
    }

    private record CountHit(long rows, String source, boolean degraded, String message) {
    }

    // ───────────────────────────── 审批与执行 ─────────────────────────────

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovDelRequestVo submit(GovDelActionParam param) {
        GovDelRequest req = requireRequest(param.getReqId());
        assertMutable(req);
        List<GovDelTarget> included = listTargets(req.getId()).stream()
                .filter(t -> !"excluded".equals(t.getStatus()))
                .toList();
        if (included.isEmpty()) {
            throw new CommonException("计划为空或全部被排除，不能提交审批");
        }
        List<String> needConfirm = included.stream()
                .filter(t -> "inferred".equalsIgnoreCase(StrUtil.blankToDefault(t.getLineageConfidence(), "")))
                .filter(t -> !Boolean.TRUE.equals(t.getLineageConfirmed()))
                .map(GovDelTarget::getObjectFqn)
                .toList();
        if (!needConfirm.isEmpty()) {
            throw new CommonException("推断血缘载体须人工确认后再提交：" + String.join("、",
                    needConfirm.stream().limit(5).toList())
                    + (needConfirm.size() > 5 ? " 等 " + needConfirm.size() + " 项" : ""));
        }
        ApplyTicketCreateParam ticketParam = new ApplyTicketCreateParam();
        ticketParam.setTicketType("compliance_delete");
        ticketParam.setTitle("合规删除 · " + req.getReqNo() + " · " + reqTypeLabel(req.getReqType()));
        ticketParam.setReason(StrUtil.blankToDefault(req.getLegalBasis(), "合规删除请求"));
        ticketParam.setReqNo(req.getReqNo());
        ticketParam.setSubjectMasked(req.getSubjectMasked());
        ticketParam.setTargetCount(included.size());
        ApplyTicket ticket = applyTicketService.create(ticketParam);

        req.setTicketNo(ticket.getTicketNo());
        req.setStatus("pending_approval");
        req.setRevision(nvlInt(req.getRevision()) + 1);
        req.setUpdateTime(new Date());
        requestMapper.updateById(req);

        logExec(req, null, "approval.submit", "success", null, null,
                "已提交申请中心：" + ticket.getTicketNo() + "（安全岗 → 法务 → 表 Owner）");
        addEvidence(req, "ticket", "审批单 " + ticket.getTicketNo(),
                JSONUtil.toJsonStr(Map.of("ticketNo", ticket.getTicketNo(), "targetCount", included.size())));
        return detail(req.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovDelRequestVo schedule(GovDelActionParam param) {
        GovDelRequest req = requireRequest(param.getReqId());
        assertNotHeld(req);
        applyTicketService.assertApprovedComplianceTicket(req.getTicketNo());
        Date window = param.getExecWindow() != null ? param.getExecWindow() : nextMaintenanceWindow();
        req.setExecWindow(window);
        req.setStatus("scheduled");
        req.setRevision(nvlInt(req.getRevision()) + 1);
        req.setUpdateTime(new Date());
        requestMapper.updateById(req);
        logExec(req, null, "exec.schedule", "success", null, null, "排期执行窗口 " + window);
        return detail(req.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovDelRequestVo execute(GovDelActionParam param) {
        GovDelRequest req = requireRequest(param.getReqId());
        if (!StrUtil.equals(StrUtil.trim(param.getConfirmReqNo()), req.getReqNo())) {
            throw new CommonException("执行前须回填请求号确认：" + req.getReqNo());
        }
        assertNotHeld(req);
        applyTicketService.assertApprovedComplianceTicket(req.getTicketNo());
        if (Set.of("done", "archived", "destroyed", "rejected").contains(req.getStatus())) {
            throw new CommonException("当前状态不可执行：" + statusLabel(req.getStatus()));
        }
        String execKey = StrUtil.blankToDefault(param.getExecKey(), "exec-" + req.getReqNo());
        Long replayed = execMapper.selectCount(new QueryWrapper<GovDelExec>().lambda()
                .eq(GovDelExec::getReqId, req.getId())
                .eq(GovDelExec::getExecKey, execKey)
                .eq(GovDelExec::getStep, "exec.finish"));
        if (replayed != null && replayed > 0) {
            return detail(req.getId());
        }

        Date now = new Date();
        req.setStatus("executing");
        req.setUpdateTime(now);
        requestMapper.updateById(req);
        logExec(req, null, "exec.start", "success", execKey, null,
                Boolean.TRUE.equals(param.getUrgent()) ? "紧急通道执行（监管责令）" : "按窗口执行");

        List<GovDelTarget> targets = listTargets(req.getId()).stream()
                .filter(t -> Set.of("planned", "failed", "running").contains(t.getStatus()))
                .sorted(Comparator.comparingInt(t -> nvlInt(t.getCarrierOrder())))
                .toList();
        int icebergRunning = 0;
        int failed = 0;
        int pending = 0;
        for (GovDelTarget t : targets) {
            switch (t.getCarrier()) {
                case "iceberg" -> {
                    if (executeIceberg(req, t, execKey)) {
                        icebergRunning++;
                    } else {
                        failed++;
                    }
                }
                case "ck", "source", "sink" -> {
                    logExec(req, t, t.getCarrier() + ".skip", "skipped", execKey, null,
                            t.getCarrier() + " 未接线，未删除，不标完成");
                    pending++;
                }
                case "export" -> {
                    finishTarget(req, t, "pending_receipt", "export.notify",
                            "已向副本持有方发出删除请求，等待回执归档", null, execKey);
                    pending++;
                }
                case "backup" -> {
                    finishTarget(req, t, "registered", "backup.register",
                            "登记备份批次到期销毁，观察期 " + BACKUP_OBSERVE_DAYS + " 天", null, execKey);
                    if (req.getDestroyAfter() == null) {
                        req.setDestroyAfter(plusDays(now, BACKUP_OBSERVE_DAYS));
                    }
                    pending++;
                }
                case "kafka" -> {
                    t.setStatus("restricted");
                    t.setExcludeReason(StrUtil.blankToDefault(t.getExcludeReason(),
                            "消息队列不支持定点删除；依赖保留期到期，期间停止再消费"));
                    t.setReviewAt(plusDays(now, 7));
                    t.setUpdateTime(now);
                    targetMapper.updateById(t);
                    logExec(req, t, "kafka.retention", "skipped", execKey, null, t.getExcludeReason());
                    pending++;
                }
                default -> {
                    logExec(req, t, t.getCarrier() + ".skip", "skipped", execKey, null,
                            "载体 " + t.getCarrier() + " 未接线，未删除，不标完成");
                    pending++;
                }
            }
        }

        req.setExecutedAt(new Date());
        req.setStatus(failed > 0 && icebergRunning == 0 ? "partial_failed" : "verifying");
        req.setRevision(nvlInt(req.getRevision()) + 1);
        req.setUpdateTime(new Date());
        requestMapper.updateById(req);
        logExec(req, null, "exec.finish", failed > 0 ? "warn" : "success", execKey, null,
                "Iceberg 已提交待验证 " + icebergRunning + " 项，未接线跳过 " + pending
                        + " 项，失败 " + failed + " 项；未反查为 0 的载体不标完成");
        return detail(req.getId());
    }

    /**
     * Iceberg：DELETE WHERE → 提交合并。定向过期与 0 行反查在 verify 里做完才标完成。
     *
     * @return true 表示 DELETE 已提交且进入 running；false 表示失败，不得标完成
     */
    private boolean executeIceberg(GovDelRequest req, GovDelTarget t, String execKey) {
        String column = subjectColumn(t);
        GovLcMetadataSql.TableRef ref;
        try {
            ref = GovLcMetadataSql.parse(t.getObjectFqn(), icebergCatalog());
            if (StrUtil.isBlank(column)) {
                throw new IllegalArgumentException("主体索引缺少 idColumn，拒绝执行");
            }
            String sql = GovDelIcebergSql.delete(ref.schema(), ref.table(), column, req.getSubjectIdHash());
            Map<String, Object> exec = trinoJob(sql, ref);
            if (Boolean.TRUE.equals(exec.get("degraded"))) {
                throw new IllegalStateException(String.valueOf(exec.get("message")));
            }
            String qid = str(exec.get("trinoQueryId"));
            logExec(req, t, "iceberg.delete", "success", execKey, qid,
                    "DELETE WHERE " + column + " = <hmac> · " + t.getObjectFqn()
                            + (StrUtil.isNotBlank(qid) ? " · " + qid : ""));

            GovLcTableActionParam lc = new GovLcTableActionParam();
            lc.setTableFqn(t.getObjectFqn());
            lc.setWs(req.getWs());
            lc.setReqNo(req.getReqNo());
            lc.setRemark("合规删除 " + req.getReqNo());
            GovLcRunVo compactRun = govLcService.compact(lc);
            logExec(req, t, "iceberg.rewrite", "submitted", execKey, compactRun.getRunId(),
                    "rewrite_data_files 已提交，待成功后再定向过期");

            t.setStatus("running");
            t.setEngineRef("delete:" + StrUtil.blankToDefault(qid, "-") + ";compact:" + compactRun.getRunId());
            t.setUpdateTime(new Date());
            targetMapper.updateById(t);
            return true;
        } catch (Exception e) {
            t.setStatus("failed");
            t.setUpdateTime(new Date());
            targetMapper.updateById(t);
            logExec(req, t, "iceberg.delete", "failed", execKey, null,
                    StrUtil.maxLength(StrUtil.blankToDefault(e.getMessage(), "Iceberg DELETE 失败"), 500));
            return false;
        }
    }

    /**
     * 合并成功后才提交 retain_last=1；过期成功且 Trino COUNT=0 才标 done。
     */
    private String advanceIceberg(GovDelRequest req, GovDelTarget t) {
        EngineRef refs = EngineRef.parse(t.getEngineRef());
        if (StrUtil.isBlank(refs.compactRunId)) {
            t.setStatus("failed");
            t.setUpdateTime(new Date());
            targetMapper.updateById(t);
            return "failed";
        }
        GovLcRunVo compact = govLcService.syncRun(refs.compactRunId);
        if (!"success".equals(compact.getStatus())) {
            if ("failed".equals(compact.getStatus())) {
                t.setStatus("failed");
                t.setUpdateTime(new Date());
                targetMapper.updateById(t);
                logExec(req, t, "iceberg.rewrite", "failed", null, compact.getRunId(),
                        StrUtil.blankToDefault(compact.getErrorMsg(), "合并失败"));
                return "failed";
            }
            t.setStatus("running");
            t.setUpdateTime(new Date());
            targetMapper.updateById(t);
            return "running";
        }
        if (StrUtil.isBlank(refs.expireRunId)) {
            GovLcTableActionParam lc = new GovLcTableActionParam();
            lc.setTableFqn(t.getObjectFqn());
            lc.setWs(req.getWs());
            lc.setReqNo(req.getReqNo());
            lc.setRetainLast(1);
            lc.setRemark("合规删除定向过期 " + req.getReqNo());
            GovLcRunVo expire = govLcService.expire(lc);
            refs.expireRunId = expire.getRunId();
            t.setEngineRef(refs.format());
            t.setStatus("running");
            t.setUpdateTime(new Date());
            targetMapper.updateById(t);
            logExec(req, t, "iceberg.expire", "submitted", null, expire.getRunId(),
                    "定向 expire_snapshots(retain_last=1) 已提交");
            return "running";
        }
        GovLcRunVo expire = govLcService.syncRun(refs.expireRunId);
        if (!"success".equals(expire.getStatus())) {
            if ("failed".equals(expire.getStatus())) {
                t.setStatus("failed");
                t.setUpdateTime(new Date());
                targetMapper.updateById(t);
                logExec(req, t, "iceberg.expire", "failed", null, expire.getRunId(),
                        StrUtil.blankToDefault(expire.getErrorMsg(), "定向过期失败"));
                return "failed";
            }
            t.setStatus("running");
            t.setUpdateTime(new Date());
            targetMapper.updateById(t);
            return "running";
        }
        long remaining = countRemaining(req, t);
        if (remaining < 0) {
            t.setStatus("running");
            t.setUpdateTime(new Date());
            targetMapper.updateById(t);
            logExec(req, t, "iceberg.verify", "failed", null, null, "Trino 反查失败，不把残留当成 0 行");
            return "running";
        }
        t.setRowsVerified(remaining);
        t.setUpdateTime(new Date());
        if (remaining == 0) {
            t.setStatus("done");
            targetMapper.updateById(t);
            logExec(req, t, "iceberg.verify", "success", null, refs.expireRunId, "反查 0 行，旧快照已定向过期");
            return "done";
        }
        t.setStatus("failed");
        targetMapper.updateById(t);
        logExec(req, t, "iceberg.verify", "failed", null, refs.expireRunId,
                "反查仍有 " + remaining + " 行，不标完成");
        return "failed";
    }

    private long countRemaining(GovDelRequest req, GovDelTarget t) {
        try {
            String column = subjectColumn(t);
            if (StrUtil.isBlank(column)) {
                return -1L;
            }
            GovLcMetadataSql.TableRef ref = GovLcMetadataSql.parse(t.getObjectFqn(), icebergCatalog());
            String sql = GovDelIcebergSql.count(ref.schema(), ref.table(), column, req.getSubjectIdHash());
            Map<String, Object> exec = trinoJob(sql, ref);
            if (Boolean.TRUE.equals(exec.get("degraded"))) {
                return -1L;
            }
            return parseCnt(exec);
        } catch (Exception e) {
            return -1L;
        }
    }

    private static long parseCnt(Map<String, Object> exec) {
        Object rows = exec.get("rows");
        if (!(rows instanceof List<?> list) || list.isEmpty() || !(list.get(0) instanceof Map<?, ?> map)) {
            return -1L;
        }
        Object cnt = map.get("cnt");
        if (cnt == null) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if ("cnt".equalsIgnoreCase(String.valueOf(e.getKey()))) {
                    cnt = e.getValue();
                    break;
                }
            }
        }
        if (cnt == null && map.size() == 1) {
            cnt = map.values().iterator().next();
        }
        if (cnt instanceof Number n) {
            return n.longValue();
        }
        if (cnt == null) {
            return -1L;
        }
        try {
            return Long.parseLong(String.valueOf(cnt).trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private Map<String, Object> trinoJob(String sql, GovLcMetadataSql.TableRef ref) {
        TrinoClient.ExecuteOptions opts = TrinoClient.ExecuteOptions.job(5);
        opts.catalog = ref.catalog();
        opts.schema = ref.schema();
        opts.timeoutMs = 120_000;
        opts.source = "job.lifecycle";
        opts.clientTags = "job.lifecycle,compliance-delete";
        return trinoClient.execute(sql, opts);
    }

    private String subjectColumn(GovDelTarget t) {
        if (StrUtil.isBlank(t.getSourceMapId())) {
            return null;
        }
        GovDelSubjectMap map = subjectMapMapper.selectById(t.getSourceMapId());
        return map == null ? null : StrUtil.trimToNull(map.getIdColumn());
    }

    private String icebergCatalog() {
        if (lhProperties.getGravitino() != null && StrUtil.isNotBlank(lhProperties.getGravitino().getCatalog())) {
            return lhProperties.getGravitino().getCatalog().trim();
        }
        return "iceberg";
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static final class EngineRef {
        private String deleteRef;
        private String compactRunId;
        private String expireRunId;

        static EngineRef parse(String raw) {
            EngineRef r = new EngineRef();
            if (StrUtil.isBlank(raw)) {
                return r;
            }
            for (String part : raw.split(";")) {
                int i = part.indexOf(':');
                if (i <= 0) {
                    continue;
                }
                String k = part.substring(0, i);
                String v = part.substring(i + 1);
                switch (k) {
                    case "delete" -> r.deleteRef = v;
                    case "compact" -> r.compactRunId = v;
                    case "expire" -> r.expireRunId = v;
                    default -> {
                    }
                }
            }
            return r;
        }

        String format() {
            return "delete:" + StrUtil.blankToDefault(deleteRef, "-")
                    + ";compact:" + StrUtil.blankToDefault(compactRunId, "-")
                    + (StrUtil.isBlank(expireRunId) ? "" : ";expire:" + expireRunId);
        }
    }

    private void finishTarget(GovDelRequest req, GovDelTarget t, String status, String step,
                              String detail, String engineRef, String execKey) {
        Date now = new Date();
        t.setStatus(status);
        if (engineRef != null) {
            t.setEngineRef(engineRef);
        }
        t.setUpdateTime(now);
        targetMapper.updateById(t);
        logExec(req, t, step, "pending_receipt".equals(status) ? "queued" : "success", execKey, engineRef, detail);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovDelRequestVo verify(GovDelActionParam param) {
        GovDelRequest req = requireRequest(param.getReqId());
        List<GovDelTarget> targets = listTargets(req.getId());
        Date now = new Date();
        int verified = 0;
        int waiting = 0;
        int failed = 0;
        for (GovDelTarget t : targets) {
            String status = StrUtil.blankToDefault(t.getStatus(), "planned");
            if ("iceberg".equals(t.getCarrier()) && "running".equals(status)) {
                status = advanceIceberg(req, t);
            }
            switch (status) {
                case "done" -> {
                    if ("iceberg".equals(t.getCarrier()) && t.getRowsVerified() == null) {
                        waiting++;
                    } else {
                        verified++;
                    }
                }
                case "pending_receipt", "registered", "restricted" -> waiting++;
                case "failed" -> failed++;
                case "excluded" -> {
                }
                default -> waiting++;
            }
        }
        boolean hasPendingDestroy = targets.stream().anyMatch(t -> "registered".equals(t.getStatus()));
        String status;
        if (failed > 0) {
            status = "partial_failed";
        } else if (hasPendingDestroy) {
            status = "archived";
        } else if (waiting > 0) {
            status = "verifying";
        } else {
            status = "done";
        }
        req.setStatus(status);
        if ("done".equals(status) || "archived".equals(status)) {
            req.setVerifiedAt(now);
        }
        req.setRevision(nvlInt(req.getRevision()) + 1);
        req.setUpdateTime(now);
        requestMapper.updateById(req);

        String detail = "Iceberg 反查为 0 的载体 " + verified + " 项；未完成 " + waiting
                + " 项；失败 " + failed + " 项。未反查为 0 不标完成";
        logExec(req, null, "verify.residual", failed > 0 ? "failed" : (waiting > 0 ? "warn" : "success"),
                param.getExecKey(), null, detail);
        addEvidence(req, "verify", "残留验证结果", JSONUtil.toJsonStr(Map.of(
                "verifiedTargets", verified,
                "waiting", waiting,
                "failed", failed,
                "note", "iceberg done only when Trino count is 0 after retain_last=1"
        )));
        return detail(req.getId());
    }

    // ───────────────────────────── 兜底与冻结 ─────────────────────────────

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovDelRequestVo restrict(GovDelRestrictParam param) {
        GovDelRequest req = requireRequest(param.getReqId());
        Date now = new Date();
        Date review = param.getReviewAt() != null ? param.getReviewAt() : plusDays(now, RESTRICT_REVIEW_DAYS);
        List<GovDelTarget> targets = listTargets(req.getId());
        List<GovDelTarget> hit = targets.stream()
                .filter(t -> param.getTargetIds() == null || param.getTargetIds().isEmpty()
                        ? !Set.of("done", "excluded").contains(t.getStatus())
                        : param.getTargetIds().contains(t.getId()))
                .toList();
        if (hit.isEmpty()) {
            throw new CommonException("没有可转限制处理的载体");
        }
        for (GovDelTarget t : hit) {
            t.setStatus("restricted");
            t.setExcludeReason(param.getReason());
            t.setReviewAt(review);
            t.setUpdateTime(now);
            targetMapper.updateById(t);
        }
        boolean allRestricted = targets.stream()
                .filter(t -> !"excluded".equals(t.getStatus()))
                .allMatch(t -> "restricted".equals(t.getStatus()));
        if (allRestricted) {
            req.setStatus("restricted");
            req.setRevision(nvlInt(req.getRevision()) + 1);
            req.setUpdateTime(now);
            requestMapper.updateById(req);
        }
        logExec(req, null, "restrict.apply", "success", null, null,
                "限制处理 " + hit.size() + " 项：撤 ACL + 强制脱敏 + 禁出湖/API/训练；复查 " + review);
        addEvidence(req, "statement", "限制处理说明", JSONUtil.toJsonStr(Map.of(
                "reason", param.getReason(),
                "targets", hit.size(),
                "reviewAt", String.valueOf(review),
                "legalBasis", "个人信息保护法 §47：删除难以实现的，停止除存储与必要安全保护之外的处理"
        )));
        return detail(req.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovDelRequestVo hold(GovDelHoldParam param) {
        GovDelRequest req = requireRequest(param.getReqId());
        if (StrUtil.isBlank(param.getReason())) {
            throw new CommonException("冻结须填写原因");
        }
        Date now = new Date();
        GovDelHold h = new GovDelHold();
        h.setId(IdUtil.getSnowflakeNextIdStr());
        h.setRevision(1);
        h.setStatus("active");
        h.setWs(req.getWs());
        h.setReqId(req.getId());
        h.setSubjectIdHash(req.getSubjectIdHash());
        h.setScope(param.getScope());
        h.setReason(param.getReason());
        h.setHoldUntil(param.getHoldUntil());
        h.setSource(StrUtil.blankToDefault(param.getSource(), "法务"));
        h.setDeleteFlag(NOT_DELETE);
        h.setCreateTime(now);
        h.setUpdateTime(now);
        holdMapper.insert(h);

        req.setStatus("on_hold");
        req.setHoldReason(param.getReason());
        req.setRevision(nvlInt(req.getRevision()) + 1);
        req.setUpdateTime(now);
        requestMapper.updateById(req);
        logExec(req, null, "hold.apply", "success", null, null, "法务冻结：" + param.getReason());
        return detail(req.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovDelRequestVo releaseHold(GovDelHoldParam param) {
        GovDelRequest req = requireRequest(param.getReqId());
        Date now = new Date();
        List<GovDelHold> holds = holdMapper.selectList(new QueryWrapper<GovDelHold>().lambda()
                .eq(GovDelHold::getReqId, req.getId())
                .eq(GovDelHold::getStatus, "active")
                .eq(GovDelHold::getDeleteFlag, NOT_DELETE));
        for (GovDelHold h : holds) {
            h.setStatus("released");
            h.setReleasedAt(now);
            h.setUpdateTime(now);
            holdMapper.updateById(h);
        }
        req.setHoldReason(null);
        req.setStatus(StrUtil.isNotBlank(req.getTicketNo()) ? "scheduled" : "assessing");
        req.setRevision(nvlInt(req.getRevision()) + 1);
        req.setUpdateTime(now);
        requestMapper.updateById(req);
        logExec(req, null, "hold.release", "success", null, null,
                "解除冻结 " + holds.size() + " 条：" + StrUtil.blankToDefault(param.getReason(), "条件消失"));
        return detail(req.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovDelRequestVo abort(GovDelActionParam param) {
        GovDelRequest req = requireRequest(param.getReqId());
        if (Set.of("executing", "done", "archived", "destroyed").contains(req.getStatus())) {
            throw new CommonException("当前状态不可中止：" + statusLabel(req.getStatus()));
        }
        if (StrUtil.isBlank(param.getRemark())) {
            throw new CommonException("中止须填写原因");
        }
        Date now = new Date();
        req.setStatus("rejected");
        req.setRemark(param.getRemark());
        req.setRevision(nvlInt(req.getRevision()) + 1);
        req.setUpdateTime(now);
        requestMapper.updateById(req);
        logExec(req, null, "request.abort", "success", null, null, "中止：" + param.getRemark());
        return detail(req.getId());
    }

    // ───────────────────────────── 证据 ─────────────────────────────

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> evidence(String reqIdOrNo) {
        GovDelRequest req = requireRequest(reqIdOrNo);
        List<GovDelTarget> targets = listTargets(req.getId());
        List<Map<String, Object>> flows = timeline(req.getId());
        List<GovDelEvidence> items = evidenceMapper.selectList(new QueryWrapper<GovDelEvidence>().lambda()
                .eq(GovDelEvidence::getReqId, req.getId())
                .eq(GovDelEvidence::getDeleteFlag, NOT_DELETE)
                .orderByAsc(GovDelEvidence::getCreateTime));

        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("reqNo", req.getReqNo());
        pkg.put("subjectMasked", req.getSubjectMasked());
        pkg.put("subjectIdHash", req.getSubjectIdHash());
        pkg.put("ticketNo", req.getTicketNo());
        pkg.put("legalBasis", req.getLegalBasis());
        pkg.put("status", req.getStatus());
        pkg.put("deadline", req.getDeadline());
        pkg.put("executedAt", req.getExecutedAt());
        pkg.put("verifiedAt", req.getVerifiedAt());
        pkg.put("destroyAfter", req.getDestroyAfter());
        pkg.put("targets", targets.stream().map(this::toTargetVo).toList());
        pkg.put("timeline", flows);
        pkg.put("items", items.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", e.getKind());
            m.put("title", e.getTitle());
            m.put("objectPath", e.getObjectPath());
            m.put("sha256", e.getSha256());
            m.put("content", e.getContent());
            m.put("createTime", e.getCreateTime());
            return m;
        }).toList());

        String json = JSONUtil.toJsonStr(pkg);
        String sha = SecureUtil.sha256(json);
        GovDelEvidence snapshot = addEvidence(req, "package", "证据包快照 " + req.getReqNo(), null);
        snapshot.setSha256(sha);
        snapshot.setObjectPath("s3://lake-audit/compliance/" + req.getReqNo() + "/evidence.json");
        evidenceMapper.updateById(snapshot);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("package", pkg);
        out.put("sha256", sha);
        out.put("objectPath", snapshot.getObjectPath());
        out.put("checklist", evidenceChecklist(req, targets, items));
        out.put("note", "对象存储落盘与 WORM 归档见 doc/合规删除-跨模块待办.md（P1）");
        return out;
    }

    private List<Map<String, Object>> evidenceChecklist(GovDelRequest req, List<GovDelTarget> targets,
                                                        List<GovDelEvidence> items) {
        Set<String> kinds = new LinkedHashSet<>(items.stream().map(GovDelEvidence::getKind).toList());
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(checkItem("工单与审批", StrUtil.isNotBlank(req.getTicketNo()), req.getTicketNo()));
        list.add(checkItem("删除计划", !targets.isEmpty(), targets.size() + " 个载体"));
        list.add(checkItem("执行流水", true, "gov_del_exec"));
        list.add(checkItem("残留验证", req.getVerifiedAt() != null, String.valueOf(req.getVerifiedAt())));
        list.add(checkItem("外部回执", targets.stream().noneMatch(t -> "pending_receipt".equals(t.getStatus())),
                "出湖副本回执"));
        list.add(checkItem("限制处理说明",
                targets.stream().noneMatch(t -> "restricted".equals(t.getStatus())) || kinds.contains("statement"),
                "个保法 §47"));
        list.add(checkItem("备份销毁", req.getDestroyAfter() == null || "destroyed".equals(req.getStatus()),
                req.getDestroyAfter() == null ? "无备份登记" : "到期 " + req.getDestroyAfter()));
        return list;
    }

    private Map<String, Object> checkItem(String name, boolean ok, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("ok", ok);
        m.put("detail", detail);
        return m;
    }

    // ───────────────────────────── 主体索引 ─────────────────────────────

    @Override
    public List<GovDelSubjectMapVo> listSubjectMaps(String ws, String subjectType, String carrier) {
        QueryWrapper<GovDelSubjectMap> qw = new QueryWrapper<GovDelSubjectMap>().checkSqlInjection();
        qw.lambda().eq(GovDelSubjectMap::getWs, wsOrDefault(ws))
                .eq(GovDelSubjectMap::getDeleteFlag, NOT_DELETE)
                .eq(StrUtil.isNotBlank(subjectType), GovDelSubjectMap::getSubjectType, StrUtil.trim(subjectType))
                .eq(StrUtil.isNotBlank(carrier), GovDelSubjectMap::getCarrier, StrUtil.trim(carrier))
                .orderByAsc(GovDelSubjectMap::getSubjectType)
                .orderByAsc(GovDelSubjectMap::getObjectFqn);
        return subjectMapMapper.selectList(qw).stream().map(this::toMapVo).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovDelSubjectMapVo upsertSubjectMap(GovDelSubjectMapUpsertParam param) {
        String workspace = wsOrDefault(param.getWs());
        String subjectType = param.getSubjectType().trim().toLowerCase(Locale.ROOT);
        String carrier = param.getCarrier().trim().toLowerCase(Locale.ROOT);
        String fqn = param.getObjectFqn().trim();
        Date now = new Date();
        GovDelSubjectMap existing = StrUtil.isNotBlank(param.getId())
                ? subjectMapMapper.selectById(param.getId())
                : subjectMapMapper.selectOne(new QueryWrapper<GovDelSubjectMap>().lambda()
                        .eq(GovDelSubjectMap::getWs, workspace)
                        .eq(GovDelSubjectMap::getSubjectType, subjectType)
                        .eq(GovDelSubjectMap::getObjectFqn, fqn)
                        .eq(GovDelSubjectMap::getDeleteFlag, NOT_DELETE)
                        .last("LIMIT 1"));
        if (StrUtil.isBlank(param.getIdColumn()) && StrUtil.isBlank(param.getJoinPath())) {
            throw new CommonException("主体索引须给出 idColumn 或 joinPath，否则无法定位主体");
        }
        GovDelSubjectMap m = existing != null ? existing : new GovDelSubjectMap();
        if (existing == null) {
            m.setId(IdUtil.getSnowflakeNextIdStr());
            m.setRevision(1);
            m.setWs(workspace);
            m.setDeleteFlag(NOT_DELETE);
            m.setCreateTime(now);
        } else {
            m.setRevision(nvlInt(m.getRevision()) + 1);
        }
        m.setStatus(StrUtil.blankToDefault(param.getStatus(), "active"));
        m.setSubjectType(subjectType);
        m.setCarrier(carrier);
        m.setObjectFqn(fqn);
        m.setIdColumn(param.getIdColumn());
        m.setJoinPath(param.getJoinPath());
        m.setDeleteMode(StrUtil.blankToDefault(param.getDeleteMode(), defaultMode(carrier)));
        m.setScopeTpl(param.getScopeTpl());
        m.setOwner(param.getOwner());
        m.setSensitivity(param.getSensitivity());
        m.setRemark(param.getRemark());
        m.setVerifiedAt(now);
        m.setUpdateTime(now);
        if (existing == null) {
            subjectMapMapper.insert(m);
        } else {
            subjectMapMapper.updateById(m);
        }
        return toMapVo(m);
    }

    @Override
    public Map<String, Object> coverage(String ws) {
        String workspace = wsOrDefault(ws);
        List<GovAsset> sensitive = govAssetMapper.selectList(new QueryWrapper<GovAsset>().lambda()
                .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                .in(GovAsset::getSensitivity, List.of("秘密", "机密")));
        Set<String> mapped = new LinkedHashSet<>();
        for (GovDelSubjectMap m : subjectMapMapper.selectList(new QueryWrapper<GovDelSubjectMap>().lambda()
                .eq(GovDelSubjectMap::getWs, workspace)
                .eq(GovDelSubjectMap::getStatus, "active")
                .eq(GovDelSubjectMap::getDeleteFlag, NOT_DELETE))) {
            mapped.add(m.getObjectFqn());
            mapped.add(shortName(m.getObjectFqn()));
        }
        List<String> gaps = new ArrayList<>();
        for (GovAsset a : sensitive) {
            String code = StrUtil.blankToDefault(a.getAssetCode(), a.getName());
            if (StrUtil.isBlank(code)) {
                continue;
            }
            if (!mapped.contains(code) && !mapped.contains(shortName(code))) {
                gaps.add(code);
            }
        }
        int total = sensitive.size();
        int covered = total - gaps.size();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sensitiveAssets", total);
        out.put("covered", covered);
        out.put("gapCount", gaps.size());
        out.put("coveragePct", total == 0 ? 100 : Math.round(covered * 100.0 / total));
        out.put("gapTables", gaps.stream().limit(50).toList());
        return out;
    }

    // ───────────────────────────── 内部 ─────────────────────────────

    /** 按主体索引展开计划，再经 lineage.expand（impact 下游）补表；已执行/限制处理/人工补充的项保留。 */
    private void buildPlan(GovDelRequest req) {
        List<GovDelTarget> existing = listTargets(req.getId());
        Set<String> keepKeys = new LinkedHashSet<>();
        Set<String> confirmedFqns = new LinkedHashSet<>();
        for (GovDelTarget t : existing) {
            if (Boolean.TRUE.equals(t.getLineageConfirmed()) && StrUtil.isNotBlank(t.getObjectFqn())) {
                confirmedFqns.add(t.getObjectFqn().toLowerCase(Locale.ROOT));
            }
            if (Boolean.TRUE.equals(t.getManualAdded())
                    || Set.of("done", "restricted", "registered", "pending_receipt", "excluded").contains(t.getStatus())) {
                keepKeys.add(t.getCarrier() + "|" + t.getObjectFqn());
            } else {
                targetMapper.deleteById(t.getId());
            }
        }

        List<GovDelSubjectMap> maps = subjectMapMapper.selectList(new QueryWrapper<GovDelSubjectMap>().lambda()
                .eq(GovDelSubjectMap::getWs, req.getWs())
                .eq(GovDelSubjectMap::getSubjectType, req.getSubjectType())
                .eq(GovDelSubjectMap::getStatus, "active")
                .eq(GovDelSubjectMap::getDeleteFlag, NOT_DELETE));
        Map<String, GovDelSubjectMap> mapByFqn = new LinkedHashMap<>();
        for (GovDelSubjectMap m : maps) {
            mapByFqn.put(m.getObjectFqn().toLowerCase(Locale.ROOT), m);
            mapByFqn.putIfAbsent(shortName(m.getObjectFqn()).toLowerCase(Locale.ROOT), m);
        }

        int created = 0;
        List<String> seedTables = new ArrayList<>();
        for (GovDelSubjectMap m : maps) {
            String key = m.getCarrier() + "|" + m.getObjectFqn();
            if (keepKeys.contains(key)) {
                seedTables.add(m.getObjectFqn());
                continue;
            }
            GovDelTarget t = newTarget(req, m.getCarrier(), m.getObjectFqn(), m.getScopeTpl(),
                    m.getDeleteMode(), m.getId());
            t.setOwner(m.getOwner());
            t.setSensitivity(m.getSensitivity());
            t.setHasSubjectCol(StrUtil.isNotBlank(m.getIdColumn()) || StrUtil.isNotBlank(m.getJoinPath()));
            t.setLineageLayer(guessLayer(m.getObjectFqn()));
            targetMapper.insert(t);
            keepKeys.add(key);
            seedTables.add(m.getObjectFqn());
            created++;
        }

        int lineageAdded = expandLineageDownstream(req, seedTables, mapByFqn, keepKeys, confirmedFqns);

        Map<String, Object> cov = coverage(req.getWs());
        logExec(req, null, "plan.assess", created + lineageAdded > 0 ? "success" : "warn", null, null,
                "命中主体索引 " + created + " 项；lineage.expand 下游 +" + lineageAdded
                        + "；高敏资产覆盖率 " + cov.get("coveragePct") + "%" + gapNote(cov));
    }

    private String gapNote(Map<String, Object> cov) {
        Object gap = cov.get("gapCount");
        return gap == null || "0".equals(String.valueOf(gap)) ? "" : "；缺口 " + gap + " 张表待登记";
    }

    /**
     * 对主体索引中的湖/CK/源表调用 impact 下游展开（lineage.expand）；soft-fail。
     * inferred 命中须人工确认；已确认的 fqn 在重建时保留确认态。
     */
    @SuppressWarnings("unchecked")
    private int expandLineageDownstream(GovDelRequest req, List<String> seedTables,
                                        Map<String, GovDelSubjectMap> mapByFqn,
                                        Set<String> keepKeys, Set<String> confirmedFqns) {
        Set<String> foci = new LinkedHashSet<>();
        for (String fqn : seedTables) {
            if (StrUtil.isBlank(fqn)) {
                continue;
            }
            String low = fqn.toLowerCase(Locale.ROOT);
            GovDelSubjectMap m = mapByFqn.get(low);
            String carrier = m != null ? m.getCarrier() : guessCarrier(fqn);
            if (Set.of("iceberg", "ck", "source").contains(carrier)) {
                foci.add(fqn);
            }
        }
        if (foci.isEmpty()) {
            return 0;
        }

        int added = 0;
        int inferred = 0;
        Set<String> seenDown = new LinkedHashSet<>();
        for (String focus : foci) {
            Map<String, Object> impact;
            try {
                impact = govLineageService.impact(null, focus, null, 0, 3, req.getWs());
            } catch (Exception ex) {
                logExec(req, null, "lineage.expand", "warn", null, null,
                        "焦点 " + focus + " 展开失败（软降级）：" + StrUtil.maxLength(ex.getMessage(), 200));
                continue;
            }
            if (impact == null) {
                continue;
            }
            Object downObj = impact.get("down");
            if (!(downObj instanceof List<?> downList)) {
                continue;
            }
            for (Object o : downList) {
                if (!(o instanceof Map<?, ?> raw)) {
                    continue;
                }
                Map<String, Object> item = (Map<String, Object>) raw;
                String table = StrUtil.blankToDefault(stringVal(item.get("key")), stringVal(item.get("assetCode")));
                if (StrUtil.isBlank(table)) {
                    continue;
                }
                String nk = table.toLowerCase(Locale.ROOT);
                if (!seenDown.add(nk)) {
                    continue;
                }
                // 已是主体索引项则跳过（避免重复）
                if (mapByFqn.containsKey(nk) || mapByFqn.containsKey(shortName(table).toLowerCase(Locale.ROOT))) {
                    continue;
                }
                String carrier = guessCarrier(table);
                String key = carrier + "|" + table;
                if (keepKeys.contains(key)) {
                    continue;
                }
                String confidence = StrUtil.blankToDefault(stringVal(item.get("confidence")), "explicit")
                        .trim().toLowerCase(Locale.ROOT);
                if (!"inferred".equals(confidence)) {
                    confidence = "explicit";
                }
                GovDelSubjectMap hit = mapByFqn.get(nk);
                GovDelTarget t = newTarget(req, carrier, table,
                        "lineage.expand ← " + focus,
                        hit != null ? hit.getDeleteMode() : defaultMode(carrier),
                        hit != null ? hit.getId() : null);
                t.setLineageConfidence(confidence);
                Object hopObj = item.get("hop");
                if (hopObj instanceof Number n) {
                    t.setLineageHop(n.intValue());
                }
                t.setLineageLayer(StrUtil.blankToDefault(stringVal(item.get("layer")), guessLayer(table)));
                t.setOwner(firstNonBlank(stringVal(item.get("owner")), stringVal(item.get("techOwner")),
                        hit != null ? hit.getOwner() : null));
                t.setSensitivity(firstNonBlank(stringVal(item.get("sensitivity")),
                        hit != null ? hit.getSensitivity() : null));
                boolean hasCol = hit != null && (StrUtil.isNotBlank(hit.getIdColumn())
                        || StrUtil.isNotBlank(hit.getJoinPath()));
                t.setHasSubjectCol(hasCol);
                if ("inferred".equals(confidence) && confirmedFqns.contains(nk)) {
                    t.setLineageConfirmed(true);
                } else {
                    t.setLineageConfirmed(false);
                }
                if ("inferred".equals(confidence) && !Boolean.TRUE.equals(t.getLineageConfirmed())) {
                    inferred++;
                }
                targetMapper.insert(t);
                keepKeys.add(key);
                added++;
            }
            logExec(req, null, "lineage.expand", "success", null, null,
                    "焦点 " + focus + " 下游 " + (impact.get("downCount") != null ? impact.get("downCount") : downList.size())
                            + " · source=" + impact.getOrDefault("source", "portal_edges"));
        }
        if (inferred > 0) {
            logExec(req, null, "lineage.expand", "warn", null, null,
                    "推断血缘 " + inferred + " 项待人工确认后方可提交审批");
        }
        return added;
    }

    private static String stringVal(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (StrUtil.isNotBlank(v)) {
                return v;
            }
        }
        return null;
    }

    private static String guessCarrier(String fqn) {
        String t = StrUtil.blankToDefault(fqn, "").toLowerCase(Locale.ROOT);
        if (t.contains("_local") || t.contains("clickhouse") || t.startsWith("ck.")) {
            return "ck";
        }
        if (t.startsWith("mysql.") || t.startsWith("postgres.") || t.startsWith("pg.")
                || t.contains(".crm.") || t.contains(".oltp.")) {
            return "sink";
        }
        return "iceberg";
    }

    private static String guessLayer(String table) {
        String t = StrUtil.blankToDefault(table, "").toLowerCase(Locale.ROOT);
        if (t.contains("ods")) {
            return "ODS";
        }
        if (t.contains("dwd")) {
            return "DWD";
        }
        if (t.contains("dws")) {
            return "DWS";
        }
        if (t.contains("ads")) {
            return "ADS";
        }
        if (t.contains("dim")) {
            return "DIM";
        }
        return "表";
    }

    private GovDelTarget newTarget(GovDelRequest req, String carrier, String objectFqn, String scope,
                                   String mode, String sourceMapId) {
        Date now = new Date();
        GovDelTarget t = new GovDelTarget();
        t.setId(IdUtil.getSnowflakeNextIdStr());
        t.setRevision(1);
        t.setStatus("planned");
        t.setWs(req.getWs());
        t.setReqId(req.getId());
        t.setCarrier(carrier);
        t.setCarrierOrder(CARRIER_ORDER.getOrDefault(carrier, 50));
        t.setObjectFqn(objectFqn);
        t.setScopeExpr(scope);
        t.setMode(StrUtil.blankToDefault(mode, defaultMode(carrier)));
        t.setRowsEst(0L);
        t.setSourceMapId(sourceMapId);
        t.setManualAdded(false);
        t.setLineageConfirmed(false);
        t.setDeleteFlag(NOT_DELETE);
        t.setCreateTime(now);
        t.setUpdateTime(now);
        return t;
    }

    private GovDelExec logExec(GovDelRequest req, GovDelTarget target, String step, String status,
                               String execKey, String runId, String detail) {
        Date now = new Date();
        GovDelExec e = new GovDelExec();
        e.setId(IdUtil.getSnowflakeNextIdStr());
        e.setRevision(1);
        e.setStatus(status);
        e.setWs(req.getWs());
        e.setReqId(req.getId());
        e.setTargetId(target != null ? target.getId() : null);
        e.setStep(step);
        e.setExecKey(execKey);
        e.setRunId(runId);
        e.setOperator(currentOperator());
        e.setDetail(StrUtil.maxLength(detail, 1000));
        e.setStartedAt(now);
        e.setEndedAt(now);
        e.setDeleteFlag(NOT_DELETE);
        e.setCreateTime(now);
        e.setUpdateTime(now);
        execMapper.insert(e);
        return e;
    }

    private GovDelEvidence addEvidence(GovDelRequest req, String kind, String title, String content) {
        Date now = new Date();
        GovDelEvidence e = new GovDelEvidence();
        e.setId(IdUtil.getSnowflakeNextIdStr());
        e.setRevision(1);
        e.setStatus("ok");
        e.setWs(req.getWs());
        e.setReqId(req.getId());
        e.setKind(kind);
        e.setTitle(title);
        e.setContent(content);
        e.setDeleteFlag(NOT_DELETE);
        e.setCreateTime(now);
        e.setUpdateTime(now);
        evidenceMapper.insert(e);
        return e;
    }

    private List<Map<String, Object>> timeline(String reqId) {
        return execMapper.selectList(new QueryWrapper<GovDelExec>().lambda()
                        .eq(GovDelExec::getReqId, reqId)
                        .eq(GovDelExec::getDeleteFlag, NOT_DELETE)
                        .orderByAsc(GovDelExec::getCreateTime))
                .stream().map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", e.getId());
                    m.put("step", e.getStep());
                    m.put("status", e.getStatus());
                    m.put("targetId", e.getTargetId());
                    m.put("runId", e.getRunId());
                    m.put("operator", e.getOperator());
                    m.put("detail", e.getDetail());
                    m.put("at", e.getCreateTime());
                    return m;
                }).toList();
    }

    private Map<String, List<GovDelTarget>> loadTargets(List<String> reqIds) {
        Map<String, List<GovDelTarget>> out = new LinkedHashMap<>();
        if (reqIds == null || reqIds.isEmpty()) {
            return out;
        }
        List<GovDelTarget> all = targetMapper.selectList(new QueryWrapper<GovDelTarget>().lambda()
                .in(GovDelTarget::getReqId, reqIds)
                .eq(GovDelTarget::getDeleteFlag, NOT_DELETE)
                .orderByAsc(GovDelTarget::getCarrierOrder));
        for (GovDelTarget t : all) {
            out.computeIfAbsent(t.getReqId(), k -> new ArrayList<>()).add(t);
        }
        return out;
    }

    private List<GovDelTarget> listTargets(String reqId) {
        return targetMapper.selectList(new QueryWrapper<GovDelTarget>().lambda()
                .eq(GovDelTarget::getReqId, reqId)
                .eq(GovDelTarget::getDeleteFlag, NOT_DELETE)
                .orderByAsc(GovDelTarget::getCarrierOrder));
    }

    private GovDelRequest requireRequest(String idOrNo) {
        if (StrUtil.isBlank(idOrNo)) {
            throw new CommonException("reqId 不能为空");
        }
        String key = idOrNo.trim();
        GovDelRequest r = requestMapper.selectById(key);
        if (r == null || !NOT_DELETE.equals(r.getDeleteFlag())) {
            r = requestMapper.selectOne(new QueryWrapper<GovDelRequest>().lambda()
                    .eq(GovDelRequest::getReqNo, key)
                    .eq(GovDelRequest::getDeleteFlag, NOT_DELETE)
                    .last("LIMIT 1"));
        }
        if (r == null) {
            throw new CommonException("合规删除请求不存在: " + key);
        }
        return r;
    }

    private void assertMutable(GovDelRequest req) {
        if (Set.of("executing", "done", "archived", "destroyed", "rejected").contains(req.getStatus())) {
            throw new CommonException("当前状态不可改计划：" + statusLabel(req.getStatus()));
        }
        assertNotHeld(req);
    }

    private void assertNotHeld(GovDelRequest req) {
        Long active = holdMapper.selectCount(new QueryWrapper<GovDelHold>().lambda()
                .eq(GovDelHold::getReqId, req.getId())
                .eq(GovDelHold::getStatus, "active")
                .eq(GovDelHold::getDeleteFlag, NOT_DELETE));
        if (active != null && active > 0) {
            throw new CommonException("请求处于法务冻结：" + StrUtil.blankToDefault(req.getHoldReason(), "见冻结记录"));
        }
    }

    private GovDelRequestVo toVo(GovDelRequest r, List<GovDelTarget> targets, boolean withTargets) {
        GovDelRequestVo vo = new GovDelRequestVo();
        vo.setId(r.getId());
        vo.setReqNo(r.getReqNo());
        vo.setWs(r.getWs());
        vo.setStatus(r.getStatus());
        vo.setStatusLabel(statusLabel(r.getStatus()));
        vo.setSubjectType(r.getSubjectType());
        vo.setSubjectMasked(r.getSubjectMasked());
        vo.setReqType(r.getReqType());
        vo.setReqTypeLabel(reqTypeLabel(r.getReqType()));
        vo.setLegalBasis(r.getLegalBasis());
        vo.setScopeLabel(r.getScopeLabel());
        vo.setSourceSystem(r.getSourceSystem());
        vo.setSourceRef(r.getSourceRef());
        vo.setTicketNo(r.getTicketNo());
        vo.setApplicant(r.getApplicant());
        vo.setDeadline(r.getDeadline());
        vo.setExecWindow(r.getExecWindow());
        vo.setExecutedAt(r.getExecutedAt());
        vo.setVerifiedAt(r.getVerifiedAt());
        vo.setDestroyAfter(r.getDestroyAfter());
        vo.setHoldReason(r.getHoldReason());
        vo.setRemark(r.getRemark());
        vo.setCreateTime(r.getCreateTime());

        Date now = new Date();
        if (r.getDeadline() != null) {
            long left = daysBetween(now, r.getDeadline());
            vo.setDaysLeft(left);
            vo.setSlaLevel(!isOpen(r.getStatus()) ? "ok" : left < 0 ? "overdue" : left <= 3 ? "warn" : "ok");
        } else {
            vo.setSlaLevel("ok");
        }

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("total", targets.size());
        plan.put("included", targets.stream().filter(t -> !"excluded".equals(t.getStatus())).count());
        plan.put("done", targets.stream().filter(t -> "done".equals(t.getStatus())).count());
        plan.put("restricted", targets.stream().filter(t -> "restricted".equals(t.getStatus())).count());
        plan.put("pending", targets.stream()
                .filter(t -> Set.of("planned", "running", "pending_receipt", "registered").contains(t.getStatus()))
                .count());
        plan.put("failed", targets.stream().filter(t -> "failed".equals(t.getStatus())).count());
        plan.put("rowsEst", targets.stream().mapToLong(t -> t.getRowsEst() == null ? 0 : t.getRowsEst()).sum());
        long pendingConfirm = targets.stream()
                .filter(t -> !"excluded".equals(t.getStatus()))
                .filter(t -> "inferred".equalsIgnoreCase(StrUtil.blankToDefault(t.getLineageConfidence(), "")))
                .filter(t -> !Boolean.TRUE.equals(t.getLineageConfirmed()))
                .count();
        plan.put("pendingConfirm", pendingConfirm);
        plan.put("lineageInferred", targets.stream()
                .filter(t -> "inferred".equalsIgnoreCase(StrUtil.blankToDefault(t.getLineageConfidence(), "")))
                .count());
        vo.setPlanSummary(plan);
        if (withTargets) {
            vo.setTargets(targets.stream().map(this::toTargetVo).toList());
        }
        return vo;
    }

    private GovDelTargetVo toTargetVo(GovDelTarget t) {
        GovDelTargetVo vo = new GovDelTargetVo();
        vo.setId(t.getId());
        vo.setReqId(t.getReqId());
        vo.setCarrier(t.getCarrier());
        vo.setCarrierLabel(CARRIER_LABEL.getOrDefault(t.getCarrier(), t.getCarrier()));
        vo.setCarrierOrder(t.getCarrierOrder());
        vo.setObjectFqn(t.getObjectFqn());
        vo.setScopeExpr(t.getScopeExpr());
        vo.setMode(t.getMode());
        vo.setModeLabel(modeLabel(t.getMode()));
        vo.setRowsEst(t.getRowsEst());
        vo.setRowsVerified(t.getRowsVerified());
        vo.setStatus(t.getStatus());
        vo.setStatusLabel(targetStatusLabel(t.getStatus()));
        vo.setEngineRef(t.getEngineRef());
        vo.setExcludeReason(t.getExcludeReason());
        vo.setReviewAt(t.getReviewAt());
        vo.setManualAdded(t.getManualAdded());
        vo.setLineageConfidence(t.getLineageConfidence());
        vo.setLineageHop(t.getLineageHop());
        vo.setLineageLayer(t.getLineageLayer());
        vo.setOwner(t.getOwner());
        vo.setSensitivity(t.getSensitivity());
        vo.setHasSubjectCol(t.getHasSubjectCol());
        vo.setLineageConfirmed(Boolean.TRUE.equals(t.getLineageConfirmed()));
        boolean needs = "inferred".equalsIgnoreCase(StrUtil.blankToDefault(t.getLineageConfidence(), ""))
                && !Boolean.TRUE.equals(t.getLineageConfirmed())
                && !"excluded".equals(t.getStatus());
        vo.setNeedsConfirm(needs);
        return vo;
    }

    private GovDelSubjectMapVo toMapVo(GovDelSubjectMap m) {
        GovDelSubjectMapVo vo = new GovDelSubjectMapVo();
        vo.setId(m.getId());
        vo.setWs(m.getWs());
        vo.setStatus(m.getStatus());
        vo.setSubjectType(m.getSubjectType());
        vo.setCarrier(m.getCarrier());
        vo.setCarrierLabel(CARRIER_LABEL.getOrDefault(m.getCarrier(), m.getCarrier()));
        vo.setObjectFqn(m.getObjectFqn());
        vo.setIdColumn(m.getIdColumn());
        vo.setJoinPath(m.getJoinPath());
        vo.setDeleteMode(m.getDeleteMode());
        vo.setScopeTpl(m.getScopeTpl());
        vo.setOwner(m.getOwner());
        vo.setSensitivity(m.getSensitivity());
        vo.setVerifiedAt(m.getVerifiedAt());
        vo.setRemark(m.getRemark());
        return vo;
    }

    private String hashSubject(String subjectType, String plain) {
        return SecureUtil.hmacSha256(resolveHmacKey()).digestHex(subjectType + ":" + plain);
    }

    /**
     * HMAC key：优先 Vault {@code platform/compliance/subject-hmac}；
     * 缺失时用 yml bootstrap 并尝试 writeIfAbsent。
     */
    private String resolveHmacKey() {
        String cached = resolvedHmacKey;
        if (StrUtil.isNotBlank(cached)) {
            return cached;
        }
        synchronized (this) {
            if (StrUtil.isNotBlank(resolvedHmacKey)) {
                return resolvedHmacKey;
            }
            LhProperties.Compliance cfg = lhProperties.getCompliance();
            String path = cfg != null && StrUtil.isNotBlank(cfg.getHmacVaultPath())
                    ? cfg.getHmacVaultPath()
                    : LhVaultPaths.COMPLIANCE_SUBJECT_HMAC;
            String fromVault = vaultClient.getString(path, "hmacKey");
            if (StrUtil.isNotBlank(fromVault)) {
                resolvedHmacKey = fromVault.trim();
                return resolvedHmacKey;
            }
            String bootstrap = cfg != null ? StrUtil.trim(cfg.getSubjectHmacKey()) : null;
            if (StrUtil.isBlank(bootstrap)) {
                bootstrap = "";
            }
            Map<String, Object> seed = new LinkedHashMap<>();
            seed.put("hmacKey", bootstrap);
            vaultClient.writeIfAbsent(path, seed);
            // 若并发已写入其它值，再读一次
            String again = vaultClient.getString(path, "hmacKey");
            resolvedHmacKey = StrUtil.blankToDefault(again, bootstrap).trim();
            return resolvedHmacKey;
        }
    }

    private String mask(String plain) {
        if (StrUtil.isBlank(plain)) {
            return "***";
        }
        String s = plain.trim();
        if (s.length() <= 4) {
            return s.charAt(0) + "***";
        }
        return s.substring(0, Math.min(5, s.length() - 2)) + "***" + s.substring(s.length() - 2);
    }

    /** 估算占位：非 Iceberg/CK 载体，或引擎不可达时的可复现量级。 */
    private long estimateRows(GovDelRequest req, GovDelTarget t) {
        int h = Math.abs((req.getSubjectIdHash() + t.getObjectFqn()).hashCode());
        return switch (t.getCarrier()) {
            case "source", "sink" -> 1L;
            case "export", "backup", "kafka" -> 0L;
            case "platform", "ai", "meta", "log" -> 1 + h % 8;
            default -> 1 + h % 2000;
        };
    }

    private String nextReqNo() {
        int year = Calendar.getInstance().get(Calendar.YEAR);
        String prefix = "DEL-" + year + "-";
        GovDelRequest last = requestMapper.selectOne(new QueryWrapper<GovDelRequest>().lambda()
                .likeRight(GovDelRequest::getReqNo, prefix)
                .orderByDesc(GovDelRequest::getReqNo)
                .last("LIMIT 1"));
        int seq = 1;
        if (last != null && last.getReqNo().length() > prefix.length()) {
            try {
                seq = Integer.parseInt(last.getReqNo().substring(prefix.length())) + 1;
            } catch (NumberFormatException ignored) {
                seq = 1;
            }
        }
        return prefix + String.format("%04d", seq);
    }

    private String defaultMode(String carrier) {
        return switch (carrier) {
            case "ck" -> "ck_mutation";
            case "source", "sink" -> "sink_delete";
            case "export" -> "notify";
            case "backup" -> "register";
            case "kafka" -> "retention";
            case "platform", "ai", "meta", "log" -> "purge";
            default -> "cow";
        };
    }

    private String shortName(String fqn) {
        if (StrUtil.isBlank(fqn)) {
            return "";
        }
        int i = fqn.lastIndexOf('.');
        return i >= 0 ? fqn.substring(i + 1) : fqn;
    }

    private boolean isOpen(String status) {
        return !Set.of("done", "destroyed", "rejected").contains(StrUtil.blankToDefault(status, ""));
    }

    private String statusLabel(String status) {
        return STATUS_LABEL.getOrDefault(StrUtil.blankToDefault(status, ""), status);
    }

    private String reqTypeLabel(String type) {
        return REQ_TYPE_LABEL.getOrDefault(StrUtil.blankToDefault(type, ""), type);
    }

    private String modeLabel(String mode) {
        return MODE_LABEL.getOrDefault(StrUtil.blankToDefault(mode, ""), mode);
    }

    private String targetStatusLabel(String status) {
        return switch (StrUtil.blankToDefault(status, "")) {
            case "planned" -> "待执行";
            case "running" -> "执行中";
            case "done" -> "已删除";
            case "failed" -> "失败";
            case "pending_receipt" -> "待回执";
            case "registered" -> "已登记待销毁";
            case "restricted" -> "限制处理";
            case "excluded" -> "已排除";
            default -> status;
        };
    }

    private long daysBetween(Date from, Date to) {
        long diff = to.getTime() - from.getTime();
        return (long) Math.floor(diff / 86400000.0);
    }

    private Date plusDays(Date from, int days) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(from);
        cal.add(Calendar.DAY_OF_MONTH, days);
        return cal.getTime();
    }

    private Date plusWorkDays(Date from, int workDays) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(from);
        int added = 0;
        while (added < workDays) {
            cal.add(Calendar.DAY_OF_MONTH, 1);
            int dow = cal.get(Calendar.DAY_OF_WEEK);
            if (dow != Calendar.SATURDAY && dow != Calendar.SUNDAY) {
                added++;
            }
        }
        return cal.getTime();
    }

    private Date nextMaintenanceWindow() {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.HOUR_OF_DAY, 2);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTime();
    }

    private String currentOperator() {
        try {
            SaBaseLoginUser login = StpLoginUserUtil.getLoginUser();
            if (login != null) {
                if (StrUtil.isNotBlank(login.getName())) {
                    return login.getName();
                }
                if (StrUtil.isNotBlank(login.getAccount())) {
                    return login.getAccount();
                }
                return String.valueOf(login.getId());
            }
        } catch (Exception ignored) {
            // 未登录 / 测试场景
        }
        return "system";
    }

    private String wsOrDefault(String ws) {
        return StrUtil.blankToDefault(ws, WS_DEFAULT);
    }

    private int nvlInt(Integer v) {
        return v == null ? 0 : v;
    }
}
