package vip.xiaonuo.lh.modular.recon.support;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.core.ws.LhWsFilters;
import vip.xiaonuo.lh.modular.recon.entity.ReconDiff;
import vip.xiaonuo.lh.modular.recon.entity.ReconGoldenEvent;
import vip.xiaonuo.lh.modular.recon.entity.ReconPartition;
import vip.xiaonuo.lh.modular.recon.entity.ReconRule;
import vip.xiaonuo.lh.modular.recon.mapper.ReconDiffMapper;
import vip.xiaonuo.lh.modular.recon.mapper.ReconGoldenEventMapper;
import vip.xiaonuo.lh.modular.recon.mapper.ReconPartitionMapper;
import vip.xiaonuo.lh.modular.recon.mapper.ReconRuleMapper;

import java.math.BigDecimal;
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
     * delist|restore：写事件并在存在分区流水时回写 golden_flag；
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
            ev.setStatus("pending");
            reconGoldenEventMapper.insert(ev);
            Map<String, Object> ticket = new LinkedHashMap<>();
            ticket.put("ticketId", "rwck-" + ev.getId());
            ticket.put("status", "pending");
            ticket.put("done", false);
            ticket.put("hint", "Trigger DS job job.reconcile." + bareTable(table) + ".rewrite_ck; "
                    + "callback to mark golden event done when CK reload finishes");
            ticket.put("dsJobHint", "job.ads_ck_loader / job.reconcile." + bareTable(table));
            out.put("status", "pending");
            out.put("ticket", ticket);
            out.put("event", goldenToMap(ev));
            return out;
        }

        ev.setStatus("done");
        reconGoldenEventMapper.insert(ev);

        int goldenFlag = "delist".equals(act) ? 0 : 1;
        int updated = reconPartitionMapper.update(null, new UpdateWrapper<ReconPartition>().lambda()
                .eq(ReconPartition::getLakeTable, table)
                .set(ReconPartition::getGoldenFlag, goldenFlag));
        out.put("status", "done");
        out.put("goldenFlag", goldenFlag);
        out.put("partitionUpdated", updated);
        out.put("event", goldenToMap(ev));
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
