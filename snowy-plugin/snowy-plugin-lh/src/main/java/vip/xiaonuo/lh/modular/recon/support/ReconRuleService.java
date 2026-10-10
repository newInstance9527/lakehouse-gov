package vip.xiaonuo.lh.modular.recon.support;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.ws.LhWsFilters;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetMapper;
import vip.xiaonuo.lh.modular.metric.entity.GovMetricMaterialize;
import vip.xiaonuo.lh.modular.metric.mapper.GovMetricMaterializeMapper;
import vip.xiaonuo.lh.modular.plat.service.PlatOutboxService;
import vip.xiaonuo.lh.modular.recon.entity.ReconDiff;
import vip.xiaonuo.lh.modular.recon.entity.ReconGoldenEvent;
import vip.xiaonuo.lh.modular.recon.entity.ReconPartition;
import vip.xiaonuo.lh.modular.recon.entity.ReconRule;
import vip.xiaonuo.lh.modular.recon.mapper.ReconDiffMapper;
import vip.xiaonuo.lh.modular.recon.mapper.ReconGoldenEventMapper;
import vip.xiaonuo.lh.modular.recon.mapper.ReconPartitionMapper;
import vip.xiaonuo.lh.modular.recon.mapper.ReconRuleMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 对账规则 / 差异下钻 / 黄金摘牌（§33 · recon_rule / recon_diff / recon_golden_event）。
 * 空列表合法，无演示种子。
 */
@Component
public class ReconRuleService {

    private static final String NOT_DELETE = "NOT_DELETE";
    private static final String DELETED = "DELETED";
    private static final BigDecimal DEFAULT_THRESHOLD = new BigDecimal("0.001");
    private static final Set<String> RULE_TYPES = Set.of(
            "row_count", "pk_hash", "amount", "partition", "type_check");
    private static final Set<String> GOLDEN_ACTIONS = Set.of("delist", "restore", "rewrite_ck");

    @Resource
    private ReconRuleMapper reconRuleMapper;
    @Resource
    private ReconDiffMapper reconDiffMapper;
    @Resource
    private ReconGoldenEventMapper reconGoldenEventMapper;
    @Resource
    private ReconPartitionMapper reconPartitionMapper;
    @Resource
    private GovMetricMaterializeMapper materializeMapper;
    @Resource
    private GovAssetMapper assetMapper;
    @Resource
    private PlatOutboxService platOutboxService;
    @Resource
    private GovReconRewriteCkDsLauncher rewriteCkDsLauncher;
    @Resource
    private LhProperties lhProperties;

    public Map<String, Object> listRules(String ws, String ruleType, Boolean enabled) {
        String workspace = LhWsFilters.listWs(ws);
        var qw = new QueryWrapper<ReconRule>().lambda()
                .eq(ReconRule::getDeleteFlag, NOT_DELETE)
                .orderByAsc(ReconRule::getRuleCode);
        if (StrUtil.isNotBlank(workspace)) {
            qw.eq(ReconRule::getWs, workspace);
        }
        if (StrUtil.isNotBlank(ruleType) && !"all".equalsIgnoreCase(ruleType)) {
            qw.eq(ReconRule::getRuleType, ruleType.trim().toLowerCase(Locale.ROOT));
        }
        if (enabled != null) {
            qw.eq(ReconRule::getEnabled, enabled ? 1 : 0);
        }
        List<ReconRule> rows = reconRuleMapper.selectList(qw);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", rows.size());
        out.put("records", rows.stream().map(this::ruleToMap).toList());
        return out;
    }

    public Map<String, Object> upsertRule(Map<String, Object> body) {
        Map<String, Object> param = body == null ? Map.of() : body;
        String id = str(param.get("id"));
        String ruleCode = str(param.get("ruleCode"));
        String ws = StrUtil.blankToDefault(str(param.get("ws")), "default");

        ReconRule existing = null;
        if (StrUtil.isNotBlank(id)) {
            existing = reconRuleMapper.selectById(id);
            if (existing != null && DELETED.equals(existing.getDeleteFlag())) {
                existing = null;
            }
        }
        if (existing == null && StrUtil.isNotBlank(ruleCode)) {
            existing = reconRuleMapper.selectOne(new QueryWrapper<ReconRule>().lambda()
                    .eq(ReconRule::getDeleteFlag, NOT_DELETE)
                    .eq(ReconRule::getWs, ws)
                    .eq(ReconRule::getRuleCode, ruleCode)
                    .last("LIMIT 1"));
        }

        String type = StrUtil.blankToDefault(str(param.get("ruleType")),
                existing != null ? existing.getRuleType() : null);
        if (StrUtil.isBlank(type)) {
            throw new IllegalArgumentException("ruleType 不能为空");
        }
        type = type.trim().toLowerCase(Locale.ROOT);
        if (!RULE_TYPES.contains(type)) {
            throw new IllegalArgumentException("ruleType 须为 row_count|pk_hash|amount|partition|type_check");
        }
        if (existing == null && StrUtil.isBlank(ruleCode)) {
            throw new IllegalArgumentException("ruleCode 不能为空");
        }

        Date now = new Date();
        if (existing == null) {
            ReconRule row = new ReconRule();
            row.setId(IdUtil.getSnowflakeNextIdStr());
            row.setWs(ws);
            row.setRuleCode(ruleCode);
            applyRuleBody(row, param, type);
            row.setDeleteFlag(NOT_DELETE);
            row.setCreateTime(now);
            row.setUpdateTime(now);
            reconRuleMapper.insert(row);
            return ruleToMap(row);
        }

        if (StrUtil.isNotBlank(ruleCode)) {
            existing.setRuleCode(ruleCode);
        }
        if (param.containsKey("ws") && StrUtil.isNotBlank(str(param.get("ws")))) {
            existing.setWs(ws);
        }
        applyRuleBody(existing, param, type);
        existing.setUpdateTime(now);
        reconRuleMapper.updateById(existing);
        return ruleToMap(existing);
    }

    public Map<String, Object> deleteRule(String id) {
        if (StrUtil.isBlank(id)) {
            throw new IllegalArgumentException("id 不能为空");
        }
        ReconRule row = reconRuleMapper.selectById(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        if (row == null || DELETED.equals(row.getDeleteFlag())) {
            out.put("deleted", false);
            out.put("reason", "not_found");
            return out;
        }
        row.setDeleteFlag(DELETED);
        row.setUpdateTime(new Date());
        reconRuleMapper.updateById(row);
        out.put("deleted", true);
        return out;
    }

    public Map<String, Object> listDiff(String ws, String lakeTable, String partitionKey,
                                        String ruleId, Integer limit) {
        String workspace = LhWsFilters.listWs(ws);
        int n = Math.max(1, Math.min(limit == null || limit <= 0 ? 50 : limit, 500));
        var qw = new QueryWrapper<ReconDiff>().lambda()
                .orderByDesc(ReconDiff::getCheckedAt)
                .last("LIMIT " + n);
        if (StrUtil.isNotBlank(workspace)) {
            qw.eq(ReconDiff::getWs, workspace);
        }
        if (StrUtil.isNotBlank(lakeTable)) {
            qw.eq(ReconDiff::getLakeTable, lakeTable.trim());
        }
        if (StrUtil.isNotBlank(partitionKey)) {
            qw.eq(ReconDiff::getPartitionKey, partitionKey.trim());
        }
        if (StrUtil.isNotBlank(ruleId)) {
            qw.eq(ReconDiff::getRuleId, ruleId.trim());
        }
        List<ReconDiff> rows = reconDiffMapper.selectList(qw);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", rows.size());
        out.put("records", rows.stream().map(this::diffToMap).toList());
        return out;
    }

    public Map<String, Object> recordDiff(Map<String, Object> body) {
        Map<String, Object> param = body == null ? Map.of() : body;
        String diffType = str(param.get("diffType"));
        if (StrUtil.isBlank(diffType)) {
            throw new IllegalArgumentException("diffType 不能为空");
        }
        ReconDiff row = new ReconDiff();
        row.setId(IdUtil.getSnowflakeNextIdStr());
        row.setWs(StrUtil.blankToDefault(str(param.get("ws")), "default"));
        row.setRuleId(str(param.get("ruleId")));
        row.setMetricCode(upper(str(param.get("metricCode"))));
        row.setLakeTable(str(param.get("lakeTable")));
        row.setPartitionKey(StrUtil.blankToDefault(str(param.get("partitionKey")),
                StrUtil.isNotBlank(str(param.get("dt"))) ? "dt=" + str(param.get("dt")) : null));
        row.setDiffType(diffType.trim().toLowerCase(Locale.ROOT));
        row.setPkValue(str(param.get("pkValue")));
        Object detail = param.get("detailJson");
        if (detail == null) {
            detail = param.get("detail");
        }
        if (detail instanceof Map<?, ?> map) {
            row.setDetailJson(JSONUtil.toJsonStr(map));
        } else if (detail != null) {
            row.setDetailJson(String.valueOf(detail));
        }
        row.setCheckedAt(new Date());
        row.setCreateTime(new Date());
        reconDiffMapper.insert(row);
        return diffToMap(row);
    }

    /**
     * 黄金摘牌 / 恢复 / 重导 CK。
     * delist|restore：写事件、回写 golden_flag、联动 materialize.recon_ok 与 gov_asset.is_gold；
     * rewrite_ck：写 pending 事件并返回 DS 作业票据桩。
     */
    public Map<String, Object> goldenAction(String lakeTable, String action, String note, String ws) {
        if (StrUtil.isBlank(lakeTable)) {
            throw new IllegalArgumentException("lakeTable 不能为空");
        }
        String act = StrUtil.blankToDefault(action, "").trim().toLowerCase(Locale.ROOT)
                .replace('-', '_');
        if ("rewriteck".equals(act)) {
            act = "rewrite_ck";
        }
        if (!GOLDEN_ACTIONS.contains(act)) {
            throw new IllegalArgumentException("action 须为 delist|restore|rewrite_ck");
        }
        String workspace = StrUtil.blankToDefault(LhWsFilters.listWs(ws), "default");
        String table = lakeTable.trim();

        ReconGoldenEvent ev = new ReconGoldenEvent();
        ev.setId(IdUtil.getSnowflakeNextIdStr());
        ev.setWs(workspace);
        ev.setLakeTable(table);
        ev.setAction(act);
        ev.setNote(note);
        ev.setTraceId("trc-" + ev.getId());
        ev.setCreateTime(new Date());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", ev.getId());
        out.put("ws", workspace);
        out.put("lakeTable", table);
        out.put("action", act);
        out.put("note", note);
        out.put("traceId", ev.getTraceId());

        if ("rewrite_ck".equals(act)) {
            Map<String, String> targets = resolveRewriteTargets(table);
            String ckTable = targets.get("ckTable");
            String metricCode = targets.get("metricCode");
            String partitionDt = java.time.LocalDate.now().toString();
            GovReconRewriteCkDsLauncher.LaunchResult launch = rewriteCkDsLauncher.launch(
                    table, ckTable, metricCode, partitionDt, ev.getId(), ev.getTraceId());
            ev.setJobRef(launch.jobRef);
            ev.setDsInstanceId(launch.processInstanceId);
            ev.setStatus(launch.ok ? "pending" : "failed");
            if (StrUtil.isBlank(ev.getNote())) {
                ev.setNote(launch.message);
            }
            reconGoldenEventMapper.insert(ev);

            Map<String, Object> ticket = new LinkedHashMap<>();
            ticket.put("ticketId", "rwck-" + ev.getId());
            ticket.put("status", ev.getStatus());
            ticket.put("done", false);
            ticket.put("jobRef", launch.jobRef);
            ticket.put("processInstanceId", launch.processInstanceId);
            ticket.put("workflowCode", launch.workflowCode);
            ticket.put("jobPrincipal", launch.jobPrincipal);
            ticket.put("degraded", launch.degraded);
            ticket.put("callbackAttached", launch.callbackAttached);
            ticket.put("message", launch.message);
            ticket.put("dsJobHint", launch.workflowCode);
            out.put("status", ev.getStatus());
            out.put("ticket", ticket);
            out.put("launch", launch.toMap());
            out.put("event", goldenToMap(ev));
            platOutboxService.appendSoft(
                    "recon.golden.rewrite_ck",
                    "recon_golden_event",
                    ev.getId(),
                    Map.of(
                            "lakeTable", table,
                            "ckTable", ckTable,
                            "jobRef", StrUtil.blankToDefault(launch.jobRef, ""),
                            "processInstanceId", StrUtil.blankToDefault(launch.processInstanceId, ""),
                            "ok", launch.ok,
                            "degraded", launch.degraded),
                    Map.of("source", "ReconRuleService", "ws", workspace));
            if (!launch.ok) {
                throw new CommonException("rewrite_ck DS 启动失败（lh.recon.allow-degraded-rewrite-ck=false）："
                        + launch.message);
            }
            return out;
        }

        ev.setStatus("done");
        reconGoldenEventMapper.insert(ev);

        int goldenFlag = "delist".equals(act) ? 0 : 1;
        int reconOk = goldenFlag;
        Date now = new Date();
        int updated = reconPartitionMapper.update(null, new UpdateWrapper<ReconPartition>().lambda()
                .eq(ReconPartition::getLakeTable, table)
                .set(ReconPartition::getGoldenFlag, goldenFlag)
                .set(ReconPartition::getCheckedAt, now));
        // 同表名后缀匹配（ads.xxx / xxx）
        int updatedBare = reconPartitionMapper.update(null, new UpdateWrapper<ReconPartition>().lambda()
                .likeLeft(ReconPartition::getLakeTable, "." + bareTable(table))
                .ne(ReconPartition::getLakeTable, table)
                .set(ReconPartition::getGoldenFlag, goldenFlag)
                .set(ReconPartition::getCheckedAt, now));
        updated += updatedBare;

        Map<String, Object> matFx = syncMaterializeByLakeTable(table, reconOk);
        Map<String, Object> assetFx = syncAssetGoldByLakeTable(table, goldenFlag);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lakeTable", table);
        payload.put("action", act);
        payload.put("goldenFlag", goldenFlag);
        payload.put("reconOk", reconOk);
        payload.put("partitionUpdated", updated);
        payload.put("materialize", matFx);
        payload.put("asset", assetFx);
        String eventId = platOutboxService.appendSoft(
                "recon.golden." + act,
                "recon_partition",
                table,
                payload,
                Map.of("source", "ReconRuleService", "ws", workspace));

        out.put("status", "done");
        out.put("goldenFlag", goldenFlag);
        out.put("partitionUpdated", updated);
        out.put("materialize", matFx);
        out.put("asset", assetFx);
        out.put("eventId", eventId);
        out.put("event", goldenToMap(ev));
        return out;
    }

    /**
     * DS/Worker 推送：rewrite_ck 完成回调。
     * body 支持 runId|eventId、status、processInstanceId、message、source。
     * success 且 {@code lh.recon.auto-restore-on-rewrite-ok=true} 时自动 restore。
     */
    public Map<String, Object> applyGoldenCallback(Map<String, Object> body) {
        if (body == null) {
            throw new CommonException("回调 body 不能为空");
        }
        String eventId = firstNonBlank(str(body.get("eventId")), str(body.get("runId")));
        if (StrUtil.isBlank(eventId)) {
            throw new CommonException("eventId/runId 必填");
        }
        ReconGoldenEvent ev = reconGoldenEventMapper.selectById(eventId.trim());
        if (ev == null) {
            throw new CommonException("黄金事件不存在：" + eventId);
        }
        String mapped = normalizeCallbackStatus(str(body.get("status")));
        if (StrUtil.isBlank(mapped) && body.get("dsState") != null) {
            mapped = normalizeCallbackStatus(String.valueOf(body.get("dsState")));
        }
        if (StrUtil.isBlank(mapped)) {
            throw new CommonException("status 必填（success|failed）");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("eventId", ev.getId());
        out.put("idempotent", false);

        if ("done".equals(ev.getStatus()) || "failed".equals(ev.getStatus())) {
            out.put("idempotent", true);
            out.put("status", ev.getStatus());
            out.put("event", goldenToMap(ev));
            return out;
        }

        String instanceId = str(body.get("processInstanceId"));
        if (StrUtil.isNotBlank(instanceId)) {
            ev.setDsInstanceId(instanceId.trim());
            if (StrUtil.isBlank(ev.getJobRef())) {
                ev.setJobRef(instanceId.trim());
            }
        }
        String msg = str(body.get("message"));
        if (StrUtil.isNotBlank(msg)) {
            String prev = StrUtil.blankToDefault(ev.getNote(), "");
            ev.setNote(StrUtil.maxLength(
                    (prev.isEmpty() ? "" : prev + " | ") + "callback: " + msg, 1000));
        } else if (body.get("source") != null) {
            String prev = StrUtil.blankToDefault(ev.getNote(), "");
            ev.setNote(StrUtil.maxLength(
                    (prev.isEmpty() ? "" : prev + " | ") + "callbackSource=" + body.get("source"), 1000));
        }
        ev.setStatus(mapped);
        reconGoldenEventMapper.updateById(ev);

        Map<String, Object> restore = null;
        boolean autoRestore = lhProperties.getRecon() == null
                || lhProperties.getRecon().isAutoRestoreOnRewriteOk();
        if ("done".equals(mapped)
                && "rewrite_ck".equals(StrUtil.blankToDefault(ev.getAction(), ""))
                && autoRestore
                && StrUtil.isNotBlank(ev.getLakeTable())) {
            try {
                restore = goldenAction(
                        ev.getLakeTable(),
                        "restore",
                        "auto-restore after rewrite_ck callback " + ev.getId(),
                        ev.getWs());
            } catch (Exception e) {
                restore = Map.of("ok", false, "message", StrUtil.blankToDefault(e.getMessage(), "restore failed"));
            }
        }

        platOutboxService.appendSoft(
                "recon.golden.callback",
                "recon_golden_event",
                ev.getId(),
                Map.of(
                        "status", mapped,
                        "lakeTable", StrUtil.blankToDefault(ev.getLakeTable(), ""),
                        "action", StrUtil.blankToDefault(ev.getAction(), ""),
                        "autoRestore", restore != null),
                Map.of("source", "ReconRuleService.applyGoldenCallback",
                        "ws", StrUtil.blankToDefault(ev.getWs(), "default")));

        out.put("status", mapped);
        out.put("event", goldenToMap(ev));
        if (restore != null) {
            out.put("restore", restore);
        }
        return out;
    }

    private static String normalizeCallbackStatus(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (Set.of("success", "succeeded", "done", "ok", "success_finish", "finished").contains(s)) {
            return "done";
        }
        if (Set.of("failed", "failure", "error", "fail", "killed", "stop", "stopped").contains(s)) {
            return "failed";
        }
        if ("pending".equals(s) || "running".equals(s)) {
            return s;
        }
        return null;
    }

    private static String firstNonBlank(String a, String b) {
        if (StrUtil.isNotBlank(a)) {
            return a;
        }
        return b;
    }

    /** 摘牌/恢复时联动物化 recon_ok，驱动看板 ready。 */
    private Map<String, Object> syncMaterializeByLakeTable(String lakeTable, int reconOk) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("updated", 0);
        String bare = bareTable(lakeTable);
        List<String> metricCodes = new ArrayList<>();
        List<ReconRule> rules = reconRuleMapper.selectList(new QueryWrapper<ReconRule>().lambda()
                .eq(ReconRule::getDeleteFlag, NOT_DELETE)
                .and(w -> w.eq(ReconRule::getLakeTable, lakeTable)
                        .or().likeLeft(ReconRule::getLakeTable, "." + bare)
                        .or().eq(ReconRule::getLakeTable, bare)));
        for (ReconRule r : rules) {
            if (StrUtil.isNotBlank(r.getMetricCode()) && !metricCodes.contains(r.getMetricCode())) {
                metricCodes.add(r.getMetricCode());
            }
        }
        List<ReconPartition> parts = reconPartitionMapper.selectList(new QueryWrapper<ReconPartition>().lambda()
                .and(w -> w.eq(ReconPartition::getLakeTable, lakeTable)
                        .or().likeLeft(ReconPartition::getLakeTable, "." + bare))
                .isNotNull(ReconPartition::getMetricCode)
                .orderByDesc(ReconPartition::getCheckedAt)
                .last("LIMIT 20"));
        for (ReconPartition p : parts) {
            if (StrUtil.isNotBlank(p.getMetricCode()) && !metricCodes.contains(p.getMetricCode())) {
                metricCodes.add(p.getMetricCode());
            }
        }
        int updated = 0;
        List<String> ids = new ArrayList<>();
        if (!metricCodes.isEmpty()) {
            List<GovMetricMaterialize> mats = materializeMapper.selectList(new QueryWrapper<GovMetricMaterialize>().lambda()
                    .eq(GovMetricMaterialize::getDeleteFlag, NOT_DELETE)
                    .eq(GovMetricMaterialize::getStatus, "active")
                    .in(GovMetricMaterialize::getMetricCode, metricCodes));
            for (GovMetricMaterialize m : mats) {
                m.setReconOk(reconOk);
                m.setRevision(m.getRevision() == null ? 1 : m.getRevision() + 1);
                materializeMapper.updateById(m);
                updated++;
                ids.add(m.getId());
            }
        }
        // 无规则时按 target_table 后缀兜底
        if (updated == 0) {
            List<GovMetricMaterialize> mats = materializeMapper.selectList(new QueryWrapper<GovMetricMaterialize>().lambda()
                    .eq(GovMetricMaterialize::getDeleteFlag, NOT_DELETE)
                    .eq(GovMetricMaterialize::getStatus, "active")
                    .and(w -> w.eq(GovMetricMaterialize::getTargetTable, lakeTable)
                            .or().likeLeft(GovMetricMaterialize::getTargetTable, "." + bare)
                            .or().eq(GovMetricMaterialize::getTargetTable, bare)));
            for (GovMetricMaterialize m : mats) {
                m.setReconOk(reconOk);
                m.setRevision(m.getRevision() == null ? 1 : m.getRevision() + 1);
                materializeMapper.updateById(m);
                updated++;
                ids.add(m.getId());
            }
        }
        out.put("updated", updated);
        out.put("ids", ids);
        out.put("metricCodes", metricCodes);
        out.put("reconOk", reconOk);
        return out;
    }

    /** 摘牌/恢复时联动门户资产 is_gold（按 om_fqn / asset_code 匹配湖表）。 */
    private Map<String, Object> syncAssetGoldByLakeTable(String lakeTable, int isGold) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("updated", 0);
        String bare = bareTable(lakeTable);
        String fqnSuffix = lakeTable.contains(".") ? lakeTable : bare;
        List<GovAsset> assets = assetMapper.selectList(new QueryWrapper<GovAsset>().lambda()
                .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                .and(w -> w.eq(GovAsset::getOmFqn, lakeTable)
                        .or().likeLeft(GovAsset::getOmFqn, "." + fqnSuffix)
                        .or().eq(GovAsset::getAssetCode, bare)
                        .or().eq(GovAsset::getName, bare)
                        .or().eq(GovAsset::getName, lakeTable)));
        int updated = 0;
        List<String> ids = new ArrayList<>();
        for (GovAsset a : assets) {
            Integer cur = a.getIsGold();
            if (cur != null && cur == isGold) {
                continue;
            }
            a.setIsGold(isGold);
            a.setRevision(a.getRevision() == null ? 1 : a.getRevision() + 1);
            a.setLastSyncAt(new Date());
            a.setLastSyncStatus(isGold == 1 ? "recon_gold_restore" : "recon_gold_delist");
            assetMapper.updateById(a);
            updated++;
            ids.add(a.getId());
        }
        out.put("updated", updated);
        out.put("ids", ids);
        out.put("isGold", isGold);
        return out;
    }

    private void applyRuleBody(ReconRule row, Map<String, Object> param, String type) {
        row.setRuleType(type);
        if (param.containsKey("ruleName") || row.getRuleName() == null) {
            row.setRuleName(str(param.get("ruleName")));
        }
        if (param.containsKey("lakeTable") || row.getLakeTable() == null) {
            row.setLakeTable(str(param.get("lakeTable")));
        }
        String ckTable = str(param.get("ckTable"));
        String ckDb = str(param.get("ckDatabase"));
        if (StrUtil.isBlank(ckDb) && StrUtil.isNotBlank(ckTable) && ckTable.contains(".")) {
            int dot = ckTable.lastIndexOf('.');
            ckDb = ckTable.substring(0, dot);
            ckTable = ckTable.substring(dot + 1);
        }
        if (param.containsKey("ckDatabase") || param.containsKey("ckTable") || row.getCkDatabase() == null) {
            if (param.containsKey("ckDatabase") || StrUtil.isNotBlank(ckDb)) {
                row.setCkDatabase(ckDb);
            }
            if (param.containsKey("ckTable") || StrUtil.isNotBlank(ckTable)) {
                row.setCkTable(ckTable);
            }
        }
        if (param.containsKey("metricCode") || row.getMetricCode() == null) {
            row.setMetricCode(upper(str(param.get("metricCode"))));
        }
        if (param.containsKey("threshold") || row.getThreshold() == null) {
            row.setThreshold(decimal(param.get("threshold"), DEFAULT_THRESHOLD));
        }
        if (param.containsKey("enabled") || row.getEnabled() == null) {
            row.setEnabled(bool01(param.get("enabled"), 1));
        }
        if (param.containsKey("cron") || row.getCron() == null) {
            row.setCron(str(param.get("cron")));
        }
        if (param.containsKey("extraJson") || param.containsKey("extra")) {
            Object extra = param.containsKey("extraJson") ? param.get("extraJson") : param.get("extra");
            if (extra instanceof Map<?, ?> map) {
                row.setExtraJson(JSONUtil.toJsonStr(map));
            } else if (extra != null) {
                row.setExtraJson(String.valueOf(extra));
            }
        }
        if (param.containsKey("lastStatus")) {
            row.setLastStatus(str(param.get("lastStatus")));
        }
        if (param.containsKey("lastChecked") && param.get("lastChecked") != null) {
            row.setLastChecked(new Date());
        }
    }

    private Map<String, Object> ruleToMap(ReconRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("ws", r.getWs());
        m.put("ruleCode", r.getRuleCode());
        m.put("ruleName", r.getRuleName());
        m.put("ruleType", r.getRuleType());
        m.put("lakeTable", r.getLakeTable());
        m.put("ckDatabase", r.getCkDatabase());
        m.put("ckTable", r.getCkTable());
        m.put("metricCode", r.getMetricCode());
        m.put("threshold", r.getThreshold());
        m.put("enabled", r.getEnabled() != null && r.getEnabled() == 1);
        m.put("cron", r.getCron());
        m.put("extraJson", parseJson(r.getExtraJson()));
        m.put("lastStatus", r.getLastStatus());
        m.put("lastChecked", r.getLastChecked());
        m.put("createTime", r.getCreateTime());
        m.put("updateTime", r.getUpdateTime());
        return m;
    }

    private Map<String, Object> diffToMap(ReconDiff r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("ws", r.getWs());
        m.put("ruleId", r.getRuleId());
        m.put("metricCode", r.getMetricCode());
        m.put("lakeTable", r.getLakeTable());
        m.put("partitionKey", r.getPartitionKey());
        m.put("diffType", r.getDiffType());
        m.put("pkValue", r.getPkValue());
        m.put("detailJson", parseJson(r.getDetailJson()));
        m.put("checkedAt", r.getCheckedAt());
        return m;
    }

    /** 解析 rewrite_ck 目标：优先 recon_rule，其次 materialize.target_table。 */
    private Map<String, String> resolveRewriteTargets(String lakeTable) {
        Map<String, String> out = new LinkedHashMap<>();
        String bare = bareTable(lakeTable);
        ReconRule rule = reconRuleMapper.selectOne(new QueryWrapper<ReconRule>().lambda()
                .eq(ReconRule::getDeleteFlag, NOT_DELETE)
                .and(w -> w.eq(ReconRule::getLakeTable, lakeTable)
                        .or().eq(ReconRule::getLakeTable, bare)
                        .or().likeLeft(ReconRule::getLakeTable, "." + bare))
                .orderByDesc(ReconRule::getUpdateTime)
                .last("LIMIT 1"));
        String ckTable = null;
        String metricCode = null;
        if (rule != null) {
            metricCode = rule.getMetricCode();
            if (StrUtil.isNotBlank(rule.getCkDatabase()) && StrUtil.isNotBlank(rule.getCkTable())) {
                ckTable = rule.getCkDatabase() + "." + rule.getCkTable();
            } else if (StrUtil.isNotBlank(rule.getCkTable())) {
                ckTable = rule.getCkTable();
            }
        }
        if (StrUtil.isBlank(ckTable) || StrUtil.isBlank(metricCode)) {
            var matQw = new QueryWrapper<GovMetricMaterialize>().lambda()
                    .eq(GovMetricMaterialize::getDeleteFlag, NOT_DELETE)
                    .eq(GovMetricMaterialize::getEngine, "clickhouse")
                    .eq(GovMetricMaterialize::getStatus, "active")
                    .orderByDesc(GovMetricMaterialize::getUpdateTime)
                    .last("LIMIT 1");
            if (StrUtil.isNotBlank(metricCode)) {
                matQw.eq(GovMetricMaterialize::getMetricCode, metricCode);
            } else {
                matQw.and(w -> w.eq(GovMetricMaterialize::getTargetTable, lakeTable)
                        .or().likeLeft(GovMetricMaterialize::getTargetTable, "." + bare)
                        .or().eq(GovMetricMaterialize::getTargetTable, bare));
            }
            GovMetricMaterialize mat = materializeMapper.selectOne(matQw);
            if (mat != null) {
                if (StrUtil.isBlank(ckTable)) {
                    ckTable = mat.getTargetTable();
                }
                if (StrUtil.isBlank(metricCode)) {
                    metricCode = mat.getMetricCode();
                }
            }
        }
        if (StrUtil.isBlank(ckTable)) {
            // 兜底：裸表名落 ads.<bare>
            ckTable = bare.contains(".") ? bare : "ads." + bare;
        }
        out.put("ckTable", ckTable);
        out.put("metricCode", StrUtil.blankToDefault(metricCode, ""));
        return out;
    }

    private Map<String, Object> goldenToMap(ReconGoldenEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("ws", e.getWs());
        m.put("lakeTable", e.getLakeTable());
        m.put("metricCode", e.getMetricCode());
        m.put("action", e.getAction());
        m.put("status", e.getStatus());
        m.put("note", e.getNote());
        m.put("traceId", e.getTraceId());
        m.put("jobRef", e.getJobRef());
        m.put("dsInstanceId", e.getDsInstanceId());
        m.put("createTime", e.getCreateTime());
        return m;
    }

    private static String bareTable(String lakeTable) {
        if (StrUtil.isBlank(lakeTable)) {
            return "unknown";
        }
        String t = lakeTable.trim();
        int dot = t.lastIndexOf('.');
        return dot >= 0 ? t.substring(dot + 1) : t;
    }

    private static Object parseJson(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        try {
            return JSONUtil.parse(raw);
        } catch (Exception e) {
            return raw;
        }
    }

    private static BigDecimal decimal(Object o, BigDecimal def) {
        if (o == null) {
            return def;
        }
        try {
            return new BigDecimal(String.valueOf(o).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static int bool01(Object o, int def) {
        if (o == null) {
            return def;
        }
        if (o instanceof Boolean b) {
            return b ? 1 : 0;
        }
        String s = String.valueOf(o).trim();
        if ("1".equals(s) || "true".equalsIgnoreCase(s)) {
            return 1;
        }
        if ("0".equals(s) || "false".equalsIgnoreCase(s)) {
            return 0;
        }
        return def;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }

    private static String upper(String s) {
        return StrUtil.isBlank(s) ? null : s.trim().toUpperCase(Locale.ROOT);
    }
}
