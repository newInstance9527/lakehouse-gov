package vip.xiaonuo.lh.modular.quality.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.xiaonuo.common.enums.CommonSortOrderEnum;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.common.page.CommonPageRequest;
import vip.xiaonuo.lh.core.engine.OpenMetadataClient;
import vip.xiaonuo.lh.modular.quality.entity.CbDqSyncWatermark;
import vip.xiaonuo.lh.modular.quality.entity.GovDqGate;
import vip.xiaonuo.lh.modular.quality.entity.GovDqRule;
import vip.xiaonuo.lh.modular.quality.entity.GovDqRuleRun;
import vip.xiaonuo.lh.modular.quality.mapper.CbDqSyncWatermarkMapper;
import vip.xiaonuo.lh.modular.quality.mapper.GovDqGateMapper;
import vip.xiaonuo.lh.modular.quality.mapper.GovDqRuleMapper;
import vip.xiaonuo.lh.modular.quality.mapper.GovDqRuleRunMapper;
import vip.xiaonuo.lh.modular.quality.param.GovDqGateUpsertParam;
import vip.xiaonuo.lh.modular.quality.param.GovDqIdParam;
import vip.xiaonuo.lh.modular.quality.param.GovDqPageParam;
import vip.xiaonuo.lh.modular.quality.param.GovDqRunAddParam;
import vip.xiaonuo.lh.modular.quality.param.GovDqRuleUpsertParam;
import vip.xiaonuo.lh.modular.quality.result.GovDqRuleVo;
import vip.xiaonuo.lh.modular.quality.service.GovDqService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 数据质量：门户规则 SoT + 运行流水；OM Profiler soft-fail
 *
 * @author lakehouse
 * @date 2026/9/19
 */
@Service
public class GovDqServiceImpl implements GovDqService {

    private static final String WS_DEFAULT = "default";
    private static final String NOT_DELETE = "NOT_DELETE";

    @Resource
    private GovDqRuleMapper ruleMapper;
    @Resource
    private GovDqRuleRunMapper runMapper;
    @Resource
    private GovDqGateMapper gateMapper;
    @Resource
    private CbDqSyncWatermarkMapper watermarkMapper;
    @Resource
    private OpenMetadataClient openMetadataClient;
    @Resource
    private vip.xiaonuo.lh.modular.catalog.support.GovAssetQualityGateReactor qualityGateReactor;

    @Override
    public Map<String, Object> overview(String ws, String range) {
        String workspace = StrUtil.blankToDefault(ws, WS_DEFAULT);
        Date since = sinceByRange(range);
        List<GovDqRule> rules = ruleMapper.selectList(new QueryWrapper<GovDqRule>().lambda()
                .eq(GovDqRule::getWs, workspace)
                .eq(GovDqRule::getDeleteFlag, NOT_DELETE)
                .eq(GovDqRule::getEnabled, 1));
        List<String> ruleIds = rules.stream().map(GovDqRule::getId).collect(Collectors.toList());
        List<GovDqRuleRun> runs = ruleIds.isEmpty() ? List.of() : runMapper.selectList(
                new QueryWrapper<GovDqRuleRun>().lambda()
                        .eq(GovDqRuleRun::getWs, workspace)
                        .in(GovDqRuleRun::getRuleId, ruleIds)
                        .ge(since != null, GovDqRuleRun::getRanAt, since));

        long total = runs.size();
        long passCnt = runs.stream().filter(r -> Integer.valueOf(1).equals(r.getPass())).count();
        long blockCnt = runs.stream().filter(r -> Integer.valueOf(1).equals(r.getBlocked())).count();
        double passRate = total == 0 ? 100.0 : (passCnt * 100.0 / total);
        double avgScore = total == 0 ? 100.0 : runs.stream()
                .map(r -> r.getOkPct() == null ? (Integer.valueOf(1).equals(r.getPass()) ? 100.0 : 0.0)
                        : r.getOkPct().doubleValue())
                .mapToDouble(Double::doubleValue).average().orElse(100.0);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("avgScore", round1(avgScore));
        r.put("passRate", round1(passRate));
        r.put("goldCount", countGold(workspace));
        r.put("blockCount", blockCnt);
        r.put("runCount", total);
        r.put("failCount", total - passCnt);
        r.put("ruleCount", rules.size());
        r.put("range", StrUtil.blankToDefault(range, "30"));
        return r;
    }

    @Override
    public List<Map<String, Object>> trend(String ws, String range) {
        String workspace = StrUtil.blankToDefault(ws, WS_DEFAULT);
        int days = parseRangeDays(range);
        Date since = daysAgo(days);
        List<GovDqRuleRun> runs = runMapper.selectList(new QueryWrapper<GovDqRuleRun>().lambda()
                .eq(GovDqRuleRun::getWs, workspace)
                .ge(GovDqRuleRun::getRanAt, since));
        Map<String, List<GovDqRuleRun>> byDay = new LinkedHashMap<>();
        for (int i = days - 1; i >= 0; i--) {
            byDay.put(dayKey(daysAgo(i)), new ArrayList<>());
        }
        for (GovDqRuleRun run : runs) {
            if (run.getRanAt() == null) continue;
            String k = dayKey(run.getRanAt());
            byDay.computeIfAbsent(k, x -> new ArrayList<>()).add(run);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<GovDqRuleRun>> e : byDay.entrySet()) {
            List<GovDqRuleRun> list = e.getValue();
            double score = list.isEmpty() ? 100.0 : list.stream()
                    .map(r -> r.getOkPct() == null ? (Integer.valueOf(1).equals(r.getPass()) ? 100.0 : 80.0)
                            : r.getOkPct().doubleValue())
                    .mapToDouble(Double::doubleValue).average().orElse(100.0);
            long blocks = list.stream().filter(r -> Integer.valueOf(1).equals(r.getBlocked())).count();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day", e.getKey());
            row.put("score", round1(score));
            row.put("blockCount", blocks);
            out.add(row);
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> typeDist(String ws) {
        String workspace = StrUtil.blankToDefault(ws, WS_DEFAULT);
        List<GovDqRule> rules = ruleMapper.selectList(new QueryWrapper<GovDqRule>().lambda()
                .eq(GovDqRule::getWs, workspace)
                .eq(GovDqRule::getDeleteFlag, NOT_DELETE));
        Map<String, Long> cnt = rules.stream().collect(Collectors.groupingBy(
                r -> StrUtil.blankToDefault(r.getRuleLevel(), "其他"), Collectors.counting()));
        long total = Math.max(1, rules.size());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : cnt.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", e.getKey());
            m.put("count", e.getValue());
            m.put("pct", round1(e.getValue() * 100.0 / total));
            out.add(m);
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> gold(String ws, Integer limit) {
        String workspace = StrUtil.blankToDefault(ws, WS_DEFAULT);
        int lim = limit == null ? 5 : Math.max(1, Math.min(limit, 50));
        // P0：按最近运行 ok_pct 聚合表级分数 Top
        List<GovDqRule> rules = ruleMapper.selectList(new QueryWrapper<GovDqRule>().lambda()
                .eq(GovDqRule::getWs, workspace)
                .eq(GovDqRule::getDeleteFlag, NOT_DELETE));
        Map<String, List<Double>> byTable = new LinkedHashMap<>();
        for (GovDqRule rule : rules) {
            GovDqRuleRun latest = latestRun(rule.getId());
            if (latest == null || latest.getOkPct() == null) continue;
            byTable.computeIfAbsent(rule.getTableName(), k -> new ArrayList<>())
                    .add(latest.getOkPct().doubleValue());
        }
        return byTable.entrySet().stream()
                .map(e -> {
                    double avg = e.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(0);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("table", e.getKey());
                    m.put("score", round1(avg));
                    m.put("gold", avg >= 95);
                    return m;
                })
                .filter(m -> Boolean.TRUE.equals(m.get("gold")))
                .sorted((a, b) -> Double.compare((Double) b.get("score"), (Double) a.get("score")))
                .limit(lim)
                .collect(Collectors.toList());
    }

    @Override
    public Page<GovDqRuleVo> pageRules(GovDqPageParam param) {
        QueryWrapper<GovDqRule> qw = new QueryWrapper<>();
        String workspace = StrUtil.blankToDefault(param.getWs(), WS_DEFAULT);
        qw.lambda().eq(GovDqRule::getWs, workspace).eq(GovDqRule::getDeleteFlag, NOT_DELETE);
        if (StrUtil.isNotBlank(param.getLayer())) {
            qw.lambda().eq(GovDqRule::getLayer, param.getLayer());
        }
        if (StrUtil.isNotBlank(param.getTableName())) {
            qw.lambda().eq(GovDqRule::getTableName, param.getTableName().trim());
        }
        if (StrUtil.isNotBlank(param.getAssetId())) {
            qw.lambda().eq(GovDqRule::getAssetId, param.getAssetId().trim());
        }
        String q = StrUtil.blankToDefault(param.getQ(), param.getKeyword());
        if (StrUtil.isNotBlank(q)) {
            qw.and(w -> w.like("rule_code", q).or().like("table_name", q)
                    .or().like("field_name", q).or().like("expr_text", q));
        }
        if (StrUtil.isNotBlank(param.getSortField())) {
            CommonSortOrderEnum.validate(param.getSortOrder());
            qw.orderBy(true, CommonSortOrderEnum.ASC.getValue().equalsIgnoreCase(param.getSortOrder()),
                    StrUtil.toUnderlineCase(param.getSortField()));
        } else {
            qw.lambda().orderByDesc(GovDqRule::getUpdateTime);
        }
        Page<GovDqRule> page = ruleMapper.selectPage(CommonPageRequest.defaultPage(), qw);
        List<GovDqRuleVo> vos = page.getRecords().stream().map(this::toVo).collect(Collectors.toList());
        if (StrUtil.isNotBlank(param.getStatus())) {
            String st = param.getStatus().toLowerCase(Locale.ROOT);
            vos = vos.stream().filter(v -> {
                if ("fail".equals(st) || "failed".equals(st)) return Boolean.FALSE.equals(v.getPass());
                if ("pass".equals(st) || "ok".equals(st)) return Boolean.TRUE.equals(v.getPass());
                if ("block".equals(st)) return Boolean.TRUE.equals(v.getBlocked());
                return true;
            }).collect(Collectors.toList());
        }
        Page<GovDqRuleVo> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(vos);
        return voPage;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovDqRuleVo upsertRule(GovDqRuleUpsertParam param) {
        String workspace = StrUtil.blankToDefault(param.getWs(), WS_DEFAULT);
        String field = StrUtil.blankToDefault(param.getFieldName(), "");
        GovDqRule existing = ruleMapper.selectOne(new QueryWrapper<GovDqRule>().lambda()
                .eq(GovDqRule::getWs, workspace)
                .eq(GovDqRule::getTableName, param.getTableName())
                .eq(GovDqRule::getFieldName, field)
                .eq(GovDqRule::getRuleCode, param.getRuleCode())
                .eq(GovDqRule::getDeleteFlag, NOT_DELETE)
                .last("LIMIT 1"));
        if (existing == null && StrUtil.isNotBlank(param.getId())) {
            existing = ruleMapper.selectById(param.getId());
        }
        Date now = new Date();
        if (existing == null) {
            existing = new GovDqRule();
            existing.setId(IdUtil.getSnowflakeNextIdStr());
            existing.setRevision(1);
            existing.setStatus("active");
            existing.setWs(workspace);
            existing.setDeleteFlag(NOT_DELETE);
            existing.setCreateTime(now);
        } else {
            existing.setRevision(existing.getRevision() == null ? 1 : existing.getRevision() + 1);
            existing.setDeleteFlag(NOT_DELETE);
        }
        existing.setRuleCode(param.getRuleCode().trim());
        existing.setRuleType(param.getRuleType().trim());
        existing.setRuleLevel(StrUtil.blankToDefault(param.getRuleLevel(), guessLevel(param.getRuleType())));
        existing.setScope(StrUtil.blankToDefault(param.getScope(), StrUtil.isBlank(field) ? "table" : "field"));
        existing.setTableName(param.getTableName().trim());
        existing.setAssetId(param.getAssetId());
        existing.setFieldName(field);
        existing.setLayer(StrUtil.blankToDefault(param.getLayer(), guessLayer(param.getTableName())));
        existing.setExprText(param.getExprText());
        existing.setSeverity(normalizeSeverity(param.getSeverity()));
        existing.setEnabled(param.getEnabled() == null || param.getEnabled() ? 1 : 0);
        existing.setOmTestFqn(param.getOmTestFqn());
        existing.setRemark(param.getRemark());
        existing.setUpdateTime(now);
        if (ruleMapper.selectById(existing.getId()) == null) {
            ruleMapper.insert(existing);
        } else {
            ruleMapper.updateById(existing);
        }
        return toVo(existing);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRule(GovDqIdParam param) {
        GovDqRule row = ruleMapper.selectById(param.getId());
        if (row == null) {
            throw new CommonException("规则不存在");
        }
        row.setDeleteFlag("DELETED");
        row.setUpdateTime(new Date());
        ruleMapper.updateById(row);
    }

    @Override
    public Page<Map<String, Object>> pageRuns(String ruleId, String ws) {
        if (StrUtil.isBlank(ruleId)) {
            throw new CommonException("ruleId 不能为空");
        }
        QueryWrapper<GovDqRuleRun> qw = new QueryWrapper<>();
        qw.lambda().eq(GovDqRuleRun::getRuleId, ruleId)
                .eq(StrUtil.isNotBlank(ws), GovDqRuleRun::getWs, ws)
                .orderByDesc(GovDqRuleRun::getRanAt);
        Page<GovDqRuleRun> page = runMapper.selectPage(CommonPageRequest.defaultPage(), qw);
        Page<Map<String, Object>> vo = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        vo.setRecords(page.getRecords().stream().map(this::runToMap).collect(Collectors.toList()));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> addRun(GovDqRunAddParam param) {
        GovDqRule rule = ruleMapper.selectById(param.getRuleId());
        if (rule == null || "DELETED".equals(rule.getDeleteFlag())) {
            throw new CommonException("规则不存在");
        }
        boolean pass = Boolean.TRUE.equals(param.getPass());
        boolean blocked = Boolean.TRUE.equals(param.getBlocked())
                || (!pass && "block".equalsIgnoreCase(rule.getSeverity()));
        GovDqRuleRun run = new GovDqRuleRun();
        run.setId(IdUtil.getSnowflakeNextIdStr());
        run.setWs(StrUtil.blankToDefault(param.getWs(), rule.getWs()));
        run.setRuleId(rule.getId());
        run.setPass(pass ? 1 : 0);
        run.setOkRows(param.getOkRows());
        run.setFailRows(param.getFailRows());
        run.setOkPct(param.getOkPct());
        run.setBlocked(blocked ? 1 : 0);
        run.setJobRunId(param.getJobRunId());
        run.setMessage(param.getMessage());
        run.setRanAt(new Date());
        run.setCreateTime(new Date());
        runMapper.insert(run);
        Map<String, Object> result = runToMap(run);
        if (blocked) {
            Map<String, Object> catalogFx = qualityGateReactor.onBlockedRun(rule, run);
            result.put("catalogEffect", catalogFx);
        }
        return result;
    }

    @Override
    public List<Map<String, Object>> listGates(String ws) {
        String workspace = StrUtil.blankToDefault(ws, WS_DEFAULT);
        return gateMapper.selectList(new QueryWrapper<GovDqGate>().lambda()
                        .eq(GovDqGate::getWs, workspace)
                        .eq(GovDqGate::getDeleteFlag, NOT_DELETE))
                .stream().map(g -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", g.getId());
                    m.put("layer", g.getLayer());
                    m.put("tableName", g.getTableName());
                    m.put("assetId", g.getAssetId());
                    m.put("minScore", g.getMinScore());
                    m.put("blockOnFail", Integer.valueOf(1).equals(g.getBlockOnFail()));
                    return m;
                }).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> upsertGate(GovDqGateUpsertParam param) {
        String workspace = StrUtil.blankToDefault(param.getWs(), WS_DEFAULT);
        GovDqGate g = StrUtil.isNotBlank(param.getId()) ? gateMapper.selectById(param.getId()) : null;
        Date now = new Date();
        if (g == null) {
            g = new GovDqGate();
            g.setId(IdUtil.getSnowflakeNextIdStr());
            g.setRevision(1);
            g.setWs(workspace);
            g.setDeleteFlag(NOT_DELETE);
            g.setCreateTime(now);
        } else {
            g.setRevision(g.getRevision() == null ? 1 : g.getRevision() + 1);
        }
        g.setAssetId(param.getAssetId());
        g.setTableName(param.getTableName());
        g.setLayer(param.getLayer());
        g.setMinScore(param.getMinScore() == null ? new BigDecimal("95.00") : param.getMinScore());
        g.setBlockOnFail(param.getBlockOnFail() == null || param.getBlockOnFail() ? 1 : 0);
        g.setUpdateTime(now);
        if (gateMapper.selectById(g.getId()) == null) {
            gateMapper.insert(g);
        } else {
            gateMapper.updateById(g);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.getId());
        m.put("minScore", g.getMinScore());
        m.put("blockOnFail", Integer.valueOf(1).equals(g.getBlockOnFail()));
        return m;
    }

    @Override
    public Map<String, Object> createTicket(String ruleId, String remark) {
        GovDqRule rule = StrUtil.isBlank(ruleId) ? null : ruleMapper.selectById(ruleId);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ticketId", IdUtil.getSnowflakeNextIdStr());
        r.put("ruleId", ruleId);
        r.put("table", rule == null ? null : rule.getTableName());
        r.put("remark", remark);
        r.put("status", "open");
        r.put("hint", "质量工单已登记；正式对接工单模块");
        return r;
    }

    @Override
    public Map<String, Object> syncOm(String ws) {
        String workspace = StrUtil.blankToDefault(ws, WS_DEFAULT);
        String markValue = String.valueOf(System.currentTimeMillis());
        CbDqSyncWatermark row = watermarkMapper.selectOne(new QueryWrapper<CbDqSyncWatermark>()
                .eq("source_system", "om_dq").eq("mark_key", "ws:" + workspace).last("LIMIT 1"));
        Date now = new Date();
        if (row == null) {
            row = new CbDqSyncWatermark();
            row.setId(IdUtil.getSnowflakeNextIdStr());
            row.setSourceSystem("om_dq");
            row.setMarkKey("ws:" + workspace);
            row.setMarkValue(markValue);
            row.setUpdateTime(now);
            watermarkMapper.insert(row);
        } else {
            row.setMarkValue(markValue);
            row.setUpdateTime(now);
            watermarkMapper.updateById(row);
        }
        Map<String, Object> omHealth = openMetadataClient.health();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("markValue", markValue);
        r.put("openmetadata", omHealth);
        r.put("hint", "P0：刷新水位；Test Suite 全量同步待接");
        return r;
    }

    private GovDqRuleVo toVo(GovDqRule rule) {
        GovDqRuleVo v = new GovDqRuleVo();
        v.setId(rule.getId());
        v.setRuleCode(rule.getRuleCode());
        v.setRuleType(rule.getRuleType());
        v.setRuleLevel(rule.getRuleLevel());
        v.setScope(rule.getScope());
        v.setTableName(rule.getTableName());
        v.setAssetId(rule.getAssetId());
        v.setFieldName(rule.getFieldName());
        v.setLayer(rule.getLayer());
        v.setExprText(rule.getExprText());
        v.setSeverity(rule.getSeverity());
        v.setEnabled(Integer.valueOf(1).equals(rule.getEnabled()));
        v.setOmTestFqn(rule.getOmTestFqn());
        GovDqRuleRun latest = latestRun(rule.getId());
        if (latest != null) {
            v.setPass(Integer.valueOf(1).equals(latest.getPass()));
            v.setOkPct(latest.getOkPct());
            v.setOkRows(latest.getOkRows());
            v.setFailRows(latest.getFailRows());
            v.setBlocked(Integer.valueOf(1).equals(latest.getBlocked()));
            v.setRanAt(latest.getRanAt());
            v.setMessage(latest.getMessage());
            if (Boolean.FALSE.equals(v.getPass())) {
                v.setStatusText(Boolean.TRUE.equals(v.getBlocked()) ? "失败·已阻断 DAG" : "失败");
            } else {
                v.setStatusText("block".equalsIgnoreCase(rule.getSeverity()) ? "启用·门禁阻断" : "启用·告警");
            }
        } else {
            v.setPass(true);
            v.setStatusText("启用·待执行");
        }
        return v;
    }

    private Map<String, Object> runToMap(GovDqRuleRun run) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", run.getId());
        m.put("ruleId", run.getRuleId());
        m.put("pass", Integer.valueOf(1).equals(run.getPass()));
        m.put("okRows", run.getOkRows());
        m.put("failRows", run.getFailRows());
        m.put("okPct", run.getOkPct());
        m.put("blocked", Integer.valueOf(1).equals(run.getBlocked()));
        m.put("jobRunId", run.getJobRunId());
        m.put("message", run.getMessage());
        m.put("ranAt", run.getRanAt());
        return m;
    }

    private GovDqRuleRun latestRun(String ruleId) {
        return runMapper.selectOne(new QueryWrapper<GovDqRuleRun>().lambda()
                .eq(GovDqRuleRun::getRuleId, ruleId)
                .orderByDesc(GovDqRuleRun::getRanAt)
                .last("LIMIT 1"));
    }

    private long countGold(String ws) {
        return gold(ws, 100).size();
    }

    private static String guessLevel(String type) {
        String t = StrUtil.blankToDefault(type, "").toLowerCase(Locale.ROOT);
        if (t.contains("枚举") || t.contains("码值") || t.contains("标准")) return "标准";
        if (t.contains("行数") || t.contains("自定义") || t.contains("业务") || t.contains("gmv")) return "业务";
        if (t.contains("时效") || t.contains("sla") || t.contains("延迟")) return "时效";
        return "技术";
    }

    private static String guessLayer(String table) {
        String t = StrUtil.blankToDefault(table, "").toLowerCase(Locale.ROOT);
        if (t.contains("ods")) return "ODS";
        if (t.contains("dwd")) return "DWD";
        if (t.contains("dws")) return "DWS";
        if (t.contains("ads")) return "ADS";
        return null;
    }

    private static String normalizeSeverity(String sev) {
        if (StrUtil.isBlank(sev)) return "alert";
        String s = sev.trim().toLowerCase(Locale.ROOT);
        if (s.contains("阻断") || s.contains("block")) return "block";
        return "alert";
    }

    private static Date sinceByRange(String range) {
        return daysAgo(parseRangeDays(range));
    }

    private static int parseRangeDays(String range) {
        if ("1".equals(range) || "today".equalsIgnoreCase(range)) return 1;
        if ("7".equals(range)) return 7;
        return 30;
    }

    private static Date daysAgo(int days) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, -days);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTime();
    }

    private static String dayKey(Date d) {
        Calendar c = Calendar.getInstance();
        c.setTime(d);
        return String.format(Locale.ROOT, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    private static double round1(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
