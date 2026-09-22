package vip.xiaonuo.lh.modular.observability.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.modular.aimodel.entity.GovAiUsageDaily;
import vip.xiaonuo.lh.modular.aimodel.mapper.GovAiUsageDailyMapper;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcTableStat;
import vip.xiaonuo.lh.modular.lifecycle.mapper.GovLcTableStatMapper;
import vip.xiaonuo.lh.modular.observability.service.LhObsCostService;
import vip.xiaonuo.lh.modular.observability.support.LhFinOpsRates;
import vip.xiaonuo.lh.modular.query.entity.CpQueryExec;
import vip.xiaonuo.lh.modular.query.mapper.CpQueryExecMapper;
import vip.xiaonuo.lh.modular.workspace.entity.GovWs;
import vip.xiaonuo.lh.modular.workspace.entity.GovWsQuota;
import vip.xiaonuo.lh.modular.workspace.mapper.GovWsMapper;
import vip.xiaonuo.lh.modular.workspace.mapper.GovWsQuotaMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 用量按 ws 聚合：存储（gov_lc_table_stat）+ 计算扫描（cp_query_exec）+ AI（gov_ai_usage_daily）。
 * <p>金额引 {@link LhFinOpsRates}（§24.3 / {@code lh.finops}），与存储 showback 同源。
 */
@Service
public class LhObsCostServiceImpl implements LhObsCostService {

    private static final String NOT_DELETE = "NOT_DELETE";
    private static final String WS_DEFAULT = "default";
    private static final long TB = LhFinOpsRates.TB;

    @Resource
    private LhProperties lhProperties;
    @Resource
    private GovLcTableStatMapper tableStatMapper;
    @Resource
    private CpQueryExecMapper execMapper;
    @Resource
    private GovAiUsageDailyMapper aiUsageMapper;
    @Resource
    private GovWsMapper wsMapper;
    @Resource
    private GovWsQuotaMapper quotaMapper;

    @Override
    public Map<String, Object> costs(String range, String group, String ws) {
        int days = parseRangeDays(range);
        String g = normalizeGroup(group);
        String filterWs = StrUtil.trim(ws);

        Map<String, Bucket> buckets = new LinkedHashMap<>();
        seedFromSpaces(buckets, filterWs);
        aggregateStorage(buckets, filterWs);
        aggregateCompute(buckets, days, filterWs);
        aggregateAi(buckets, days, filterWs);

        List<Map<String, Object>> items = new ArrayList<>();
        BigDecimal totalAll = BigDecimal.ZERO;
        long maxScan = 1L;
        for (Bucket b : buckets.values()) {
            maxScan = Math.max(maxScan, b.scanBytes);
        }
        for (Bucket b : buckets.values()) {
            if (!b.seeded && b.isEmpty()) {
                continue;
            }
            Map<String, Object> row = toItem(b, days, maxScan);
            totalAll = totalAll.add((BigDecimal) row.get("totalCost"));
            items.add(row);
        }
        items.sort(Comparator
                .comparing((Map<String, Object> m) -> (BigDecimal) m.get("totalCost"))
                .reversed());

        Map<String, Object> rates = LhFinOpsRates.ratesMap(lhProperties);

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("totalCost", totalAll.setScale(2, RoundingMode.HALF_UP));
        totals.put("itemCount", items.size());
        totals.put("storageBytes", items.stream().mapToLong(m -> (Long) m.get("storageBytes")).sum());
        totals.put("scanBytes", items.stream().mapToLong(m -> (Long) m.get("scanBytes")).sum());
        totals.put("queryCount", items.stream().mapToLong(m -> (Long) m.get("queryCount")).sum());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("range", days + "d");
        out.put("group", g);
        out.put("ws", StrUtil.blankToDefault(filterWs, null));
        out.put("unit", LhFinOpsRates.currency(lhProperties));
        out.put("rates", rates);
        out.put("items", items);
        out.put("totals", totals);
        out.put("source", List.of("gov_lc_table_stat", "cp_query_exec", "gov_ai_usage_daily", "gov_ws_quota", "lh.finops"));
        return out;
    }

    private void seedFromSpaces(Map<String, Bucket> buckets, String filterWs) {
        List<GovWs> spaces = wsMapper.selectList(new QueryWrapper<GovWs>().lambda()
                .eq(GovWs::getDeleteFlag, NOT_DELETE)
                .eq(GovWs::getStatus, "active")
                .orderByAsc(GovWs::getWsCode));
        for (GovWs w : spaces) {
            String code = StrUtil.blankToDefault(w.getWsCode(), WS_DEFAULT);
            if (StrUtil.isNotBlank(filterWs) && !filterWs.equals(code)) {
                continue;
            }
            Bucket b = buckets.computeIfAbsent(code, Bucket::new);
            b.seeded = true;
            b.name = StrUtil.blankToDefault(w.getName(), code);
            b.owner = StrUtil.blankToDefault(w.getOwners(), "—");
            b.costCenter = w.getCostCenter();
        }
        List<GovWsQuota> quotas = quotaMapper.selectList(new QueryWrapper<GovWsQuota>().lambda()
                .eq(GovWsQuota::getDeleteFlag, NOT_DELETE));
        for (GovWsQuota q : quotas) {
            String code = StrUtil.blankToDefault(q.getWsCode(), WS_DEFAULT);
            if (StrUtil.isNotBlank(filterWs) && !filterWs.equals(code)) {
                continue;
            }
            Bucket b = buckets.computeIfAbsent(code, Bucket::new);
            if (q.getStorageQuotaTb() != null) {
                b.quotaBytes = q.getStorageQuotaTb()
                        .multiply(BigDecimal.valueOf(TB))
                        .longValue();
            }
        }
        if (buckets.isEmpty()) {
            buckets.computeIfAbsent(WS_DEFAULT, Bucket::new).name = "默认空间";
        }
    }

    private void aggregateStorage(Map<String, Bucket> buckets, String filterWs) {
        var qw = new QueryWrapper<GovLcTableStat>().lambda()
                .eq(GovLcTableStat::getDeleteFlag, NOT_DELETE);
        if (StrUtil.isNotBlank(filterWs)) {
            qw.eq(GovLcTableStat::getWs, filterWs);
        }
        List<GovLcTableStat> rows = tableStatMapper.selectList(qw);
        for (GovLcTableStat s : rows) {
            String code = StrUtil.blankToDefault(s.getWs(), WS_DEFAULT);
            Bucket b = buckets.computeIfAbsent(code, Bucket::new);
            long active = s.getActiveBytes() != null ? s.getActiveBytes() : nvl(s.getSizeBytes());
            long reclaim = nvl(s.getReclaimableBytes());
            b.storageActiveBytes += Math.max(0, active);
            b.storageTotalBytes += Math.max(0, active) + Math.max(0, reclaim);
        }
    }

    private void aggregateCompute(Map<String, Bucket> buckets, int days, String filterWs) {
        Date from = daysAgo(days);
        var qw = new QueryWrapper<CpQueryExec>()
                .eq("delete_flag", NOT_DELETE)
                .ge("create_time", from);
        if (StrUtil.isNotBlank(filterWs)) {
            qw.eq("ws", filterWs);
        }
        List<CpQueryExec> rows = execMapper.selectList(qw);
        for (CpQueryExec e : rows) {
            String code = StrUtil.blankToDefault(e.getWs(), WS_DEFAULT);
            Bucket b = buckets.computeIfAbsent(code, Bucket::new);
            b.queryCount++;
            b.scanBytes += Math.max(0, nvl(e.getScanBytes()));
        }
    }

    private void aggregateAi(Map<String, Bucket> buckets, int days, String filterWs) {
        Date from = daysAgo(days);
        var qw = new QueryWrapper<GovAiUsageDaily>().lambda()
                .ge(GovAiUsageDaily::getDay, from);
        if (StrUtil.isNotBlank(filterWs)) {
            qw.and(w -> w.eq(GovAiUsageDaily::getWs, filterWs).or().eq(GovAiUsageDaily::getWs, "*"));
        }
        List<GovAiUsageDaily> rows = aiUsageMapper.selectList(qw);
        for (GovAiUsageDaily u : rows) {
            String code = StrUtil.blankToDefault(u.getWs(), WS_DEFAULT);
            if ("*".equals(code)) {
                code = StrUtil.blankToDefault(filterWs, WS_DEFAULT);
            }
            if (StrUtil.isNotBlank(filterWs) && !filterWs.equals(code) && !"*".equals(u.getWs())) {
                continue;
            }
            Bucket b = buckets.computeIfAbsent(code, Bucket::new);
            b.aiCalls += nvl(u.getCalls());
            b.aiPromptTokens += nvl(u.getPromptTokens());
            b.aiCompletionTokens += nvl(u.getCompletionTokens());
            if (u.getCostAmount() != null) {
                b.aiCost = b.aiCost.add(u.getCostAmount());
            }
        }
    }

    private Map<String, Object> toItem(Bucket b, int days, long maxScan) {
        BigDecimal storageCost = LhFinOpsRates.storageCost(
                b.storageTotalBytes, days, LhFinOpsRates.storagePerTbMonth(lhProperties));
        BigDecimal computeCost = LhFinOpsRates.computeCost(
                b.scanBytes, LhFinOpsRates.computePerGbScan(lhProperties));
        BigDecimal aiCost = b.aiCost.setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = storageCost.add(computeCost).add(aiCost).setScale(2, RoundingMode.HALF_UP);

        int quotaPct = 0;
        if (b.quotaBytes > 0) {
            quotaPct = (int) Math.min(999, Math.round(b.storageTotalBytes * 100.0 / b.quotaBytes));
        }
        int barPct = maxScan <= 0 ? 0 : (int) Math.min(100, Math.round(b.scanBytes * 100.0 / maxScan));
        if (barPct == 0 && total.compareTo(BigDecimal.ZERO) > 0) {
            barPct = 8;
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", b.ws);
        m.put("ws", b.ws);
        m.put("name", StrUtil.blankToDefault(b.name, b.ws));
        m.put("owner", StrUtil.blankToDefault(b.owner, "—"));
        m.put("costCenter", b.costCenter);
        m.put("storageBytes", b.storageTotalBytes);
        m.put("storageActiveBytes", b.storageActiveBytes);
        m.put("storageCost", storageCost);
        m.put("scanBytes", b.scanBytes);
        m.put("queryCount", b.queryCount);
        m.put("computeCost", computeCost);
        m.put("aiCalls", b.aiCalls);
        m.put("aiTokens", b.aiPromptTokens + b.aiCompletionTokens);
        m.put("aiCost", aiCost);
        m.put("totalCost", total);
        m.put("totalLabel", LhFinOpsRates.formatCny(total));
        m.put("quotaBytes", b.quotaBytes);
        m.put("quotaPct", quotaPct);
        m.put("pct", barPct);
        m.put("trend", quotaPct >= 80 ? "配额预警" : (b.queryCount > 0 ? "扫描活跃" : "—"));
        return m;
    }

    private static String normalizeGroup(String group) {
        String g = StrUtil.blankToDefault(group, "ws").trim().toLowerCase(Locale.ROOT);
        if ("workspace".equals(g) || "workspace_id".equals(g) || "space".equals(g)) {
            return "ws";
        }
        return g;
    }

    private static int parseRangeDays(String range) {
        if (StrUtil.isBlank(range)) {
            return 30;
        }
        String r = range.trim().toLowerCase(Locale.ROOT);
        if (r.startsWith("7")) {
            return 7;
        }
        if (r.startsWith("90")) {
            return 90;
        }
        if (r.startsWith("30")) {
            return 30;
        }
        try {
            int n = Integer.parseInt(r.replaceAll("[^0-9]", ""));
            if (n <= 0) {
                return 30;
            }
            return Math.min(365, n);
        } catch (Exception e) {
            return 30;
        }
    }

    private static Date daysAgo(int days) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -Math.max(1, days));
        return cal.getTime();
    }

    private static long nvl(Long v) {
        return v == null ? 0L : v;
    }

    private static final class Bucket {
        final String ws;
        boolean seeded;
        String name;
        String owner;
        String costCenter;
        long storageActiveBytes;
        long storageTotalBytes;
        long quotaBytes;
        long scanBytes;
        long queryCount;
        long aiCalls;
        long aiPromptTokens;
        long aiCompletionTokens;
        BigDecimal aiCost = BigDecimal.ZERO;

        Bucket(String ws) {
            this.ws = ws;
            this.name = ws;
        }

        boolean isEmpty() {
            return storageTotalBytes <= 0 && scanBytes <= 0 && queryCount <= 0
                    && aiCalls <= 0 && aiCost.compareTo(BigDecimal.ZERO) <= 0;
        }
    }
}
