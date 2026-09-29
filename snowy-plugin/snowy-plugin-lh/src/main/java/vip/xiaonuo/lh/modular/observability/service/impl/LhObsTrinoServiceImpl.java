package vip.xiaonuo.lh.modular.observability.service.impl;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import vip.xiaonuo.lh.modular.observability.service.LhObsTrinoService;
import vip.xiaonuo.lh.modular.query.param.CpQueryHistoryParam;
import vip.xiaonuo.lh.modular.query.service.CpQueryService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LhObsTrinoServiceImpl implements LhObsTrinoService {

    @Resource
    private CpQueryService cpQueryService;

    @Override
    public Map<String, Object> queues(String ws) {
        Map<String, Object> overview = cpQueryService.govOverview();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ws", blank(ws));
        r.put("queues", overview.getOrDefault("queues", List.of()));
        r.put("adhocMaxConcurrent", overview.get("adhocMaxConcurrent"));
        r.put("adhocConcurrent", overview.get("adhocConcurrent"));
        r.put("source", "cp_query_gov_overview");
        r.put("hint", "一期门面为配置型队列；真 JMX 队列见 F4 现网依赖");
        return r;
    }

    @Override
    public Map<String, Object> topUsers(String ws, String range) {
        CpQueryHistoryParam hp = new CpQueryHistoryParam();
        hp.setLimit(200);
        hp.setMineOnly(false);
        List<Map<String, Object>> audits = cpQueryService.history(hp);
        Map<String, long[]> agg = new HashMap<>();
        for (Map<String, Object> a : audits) {
            String user = StrUtil.blankToDefault(str(a.get("user")), str(a.get("createUser")));
            if (StrUtil.isBlank(user)) {
                user = "unknown";
            }
            long bytes = asLong(a.get("scanBytes"));
            long[] slot = agg.computeIfAbsent(user, k -> new long[2]);
            slot[0]++;
            slot[1] += bytes;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, long[]> e : agg.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("user", e.getKey());
            row.put("queryCount", e.getValue()[0]);
            row.put("scanBytes", e.getValue()[1]);
            rows.add(row);
        }
        rows.sort(Comparator.comparingLong((Map<String, Object> m) -> asLong(m.get("scanBytes"))).reversed());
        if (rows.size() > 20) {
            rows = rows.subList(0, 20);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ws", blank(ws));
        r.put("range", StrUtil.blankToDefault(range, "recent"));
        r.put("records", rows);
        r.put("total", rows.size());
        r.put("source", rows.isEmpty() ? "empty" : "cp_query_exec");
        return r;
    }

    @Override
    public Map<String, Object> slow(String ws, String range, Integer limit) {
        int n = limit == null ? 20 : Math.max(1, Math.min(limit, 100));
        CpQueryHistoryParam hp = new CpQueryHistoryParam();
        hp.setLimit(200);
        hp.setMineOnly(false);
        List<Map<String, Object>> audits = new ArrayList<>(cpQueryService.history(hp));
        audits.sort(Comparator
                .comparingLong((Map<String, Object> a) -> asLong(a.get("durationMs"))).reversed()
                .thenComparingLong((Map<String, Object> a) -> asLong(a.get("scanBytes"))).reversed());
        List<Map<String, Object>> slow = audits.size() > n ? audits.subList(0, n) : audits;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ws", blank(ws));
        r.put("range", StrUtil.blankToDefault(range, "recent"));
        r.put("records", slow);
        r.put("total", slow.size());
        r.put("source", slow.isEmpty() ? "empty" : "cp_query_exec");
        return r;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String blank(String ws) {
        return StrUtil.isBlank(ws) ? null : ws.trim();
    }

    private static long asLong(Object o) {
        if (o == null) {
            return 0L;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return 0L;
        }
    }
}
