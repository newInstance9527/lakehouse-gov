package vip.xiaonuo.lh.modular.observability.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import vip.xiaonuo.lh.modular.observability.entity.GovObsSpan;
import vip.xiaonuo.lh.modular.observability.mapper.GovObsSpanMapper;
import vip.xiaonuo.lh.modular.observability.service.LhObsSpanService;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class LhObsSpanServiceImpl implements LhObsSpanService {

    private static final String NOT_DELETE = "NOT_DELETE";
    private static final String[] LINK_IDS = {
            "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L"
    };
    private static final Map<String, String> LINK_NAMES = Map.ofEntries(
            Map.entry("A", "CDC 入湖"),
            Map.entry("B", "埋点实时"),
            Map.entry("C", "离线日批"),
            Map.entry("D", "即席查询"),
            Map.entry("E", "申请授权"),
            Map.entry("F", "质量校验"),
            Map.entry("G", "血缘变更"),
            Map.entry("H", "根因(消费)"),
            Map.entry("I", "指标对账"),
            Map.entry("J", "出湖同步"),
            Map.entry("K", "合规删除"),
            Map.entry("L", "作业发布"));

    @Resource
    private GovObsSpanMapper spanMapper;

    @Override
    public Map<String, Object> linksOverview(String ws) {
        String workspace = vip.xiaonuo.lh.core.ws.LhWsFilters.listWs(ws);
        Date dayStart = startOfDay();
        List<GovObsSpan> today = spanMapper.selectList(new QueryWrapper<GovObsSpan>().lambda()
                .eq(GovObsSpan::getDeleteFlag, NOT_DELETE)
                .eq(StrUtil.isNotBlank(workspace), GovObsSpan::getWs, workspace)
                .ge(GovObsSpan::getStartTs, dayStart));

        Map<String, List<GovObsSpan>> byLink = new LinkedHashMap<>();
        for (String id : LINK_IDS) {
            byLink.put(id, new ArrayList<>());
        }
        long slow = 0;
        long failed = 0;
        for (GovObsSpan s : today) {
            String lid = StrUtil.blankToDefault(s.getLinkId(), "").toUpperCase(Locale.ROOT);
            if (byLink.containsKey(lid)) {
                byLink.get(lid).add(s);
            }
            if (s.getDurationMs() != null && s.getDurationMs() >= 3000) {
                slow++;
            }
            if ("error".equalsIgnoreCase(s.getStatus())) {
                failed++;
            }
        }

        List<Map<String, Object>> links = new ArrayList<>();
        for (String id : LINK_IDS) {
            List<GovObsSpan> list = byLink.get(id);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("linkId", id);
            item.put("name", LINK_NAMES.get(id));
            item.put("spanCount", list.size());
            item.put("p50", percentile(list, 0.50));
            item.put("p99", percentile(list, 0.99));
            item.put("errorRate", errorRate(list));
            links.add(item);
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ws", workspace);
        r.put("links", links);
        r.put("todaySpans", today.size());
        r.put("slowSpans", slow);
        r.put("failedSpans", failed);
        r.put("sampleCoverage", today.isEmpty() ? null : 100);
        r.put("source", today.isEmpty() ? "empty" : "gov_obs_span");
        return r;
    }

    @Override
    public Map<String, Object> listSpans(String ws, String linkId, String traceId, String runId, String eventId,
                                        String status, long current, long size) {
        String workspace = vip.xiaonuo.lh.core.ws.LhWsFilters.listWs(ws);
        long cur = Math.max(1, current);
        long sz = Math.min(200, Math.max(1, size));
        QueryWrapper<GovObsSpan> qw = new QueryWrapper<>();
        qw.lambda()
                .eq(GovObsSpan::getDeleteFlag, NOT_DELETE)
                .eq(StrUtil.isNotBlank(workspace), GovObsSpan::getWs, workspace)
                .eq(StrUtil.isNotBlank(linkId), GovObsSpan::getLinkId, linkId)
                .eq(StrUtil.isNotBlank(traceId), GovObsSpan::getTraceId, traceId)
                .eq(StrUtil.isNotBlank(runId), GovObsSpan::getRunId, runId)
                .eq(StrUtil.isNotBlank(eventId), GovObsSpan::getEventId, eventId)
                .eq(StrUtil.isNotBlank(status), GovObsSpan::getStatus, status)
                .orderByAsc(GovObsSpan::getStartTs);
        Page<GovObsSpan> page = spanMapper.selectPage(new Page<>(cur, sz), qw);
        List<Map<String, Object>> records = new ArrayList<>();
        for (GovObsSpan s : page.getRecords()) {
            records.add(toSpanRow(s));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("records", records);
        r.put("total", page.getTotal());
        r.put("current", cur);
        r.put("size", sz);
        r.put("source", records.isEmpty() ? "empty" : "gov_obs_span");
        return r;
    }

    @Override
    public Map<String, Object> trace(String traceId, String ws) {
        String workspace = vip.xiaonuo.lh.core.ws.LhWsFilters.listWs(ws);
        if (StrUtil.isBlank(traceId)) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("traceId", traceId);
            empty.put("spans", List.of());
            empty.put("source", "empty");
            return empty;
        }
        List<GovObsSpan> spans = spanMapper.selectList(new QueryWrapper<GovObsSpan>().lambda()
                .eq(GovObsSpan::getDeleteFlag, NOT_DELETE)
                .eq(StrUtil.isNotBlank(workspace), GovObsSpan::getWs, workspace)
                .eq(GovObsSpan::getTraceId, traceId)
                .orderByAsc(GovObsSpan::getStartTs));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (GovObsSpan s : spans) {
            rows.add(toSpanRow(s));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("traceId", traceId);
        r.put("spans", rows);
        r.put("count", rows.size());
        r.put("source", rows.isEmpty() ? "empty" : "gov_obs_span");
        return r;
    }

    @Override
    public Map<String, Object> searchLogs(String ws, String q, String traceId, String runId, String eventId,
                                         long current, long size) {
        // P0：无独立日志表，回落 span.error / attrs 检索
        Map<String, Object> spans = listSpans(ws, null, traceId, runId, eventId, null, current, size);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) spans.get("records");
        List<Map<String, Object>> lines = new ArrayList<>();
        String kw = StrUtil.trim(q);
        for (Map<String, Object> s : records) {
            String err = str(s.get("error"));
            String op = str(s.get("op"));
            String service = str(s.get("service"));
            if (StrUtil.isNotBlank(kw)) {
                String hay = (err + " " + op + " " + service).toLowerCase(Locale.ROOT);
                if (!hay.contains(kw.toLowerCase(Locale.ROOT))) {
                    continue;
                }
            }
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("time", s.get("startTs"));
            line.put("level", "error".equalsIgnoreCase(str(s.get("status"))) ? "ERROR" : "INFO");
            line.put("message", StrUtil.blankToDefault(err, service + " " + op));
            line.put("traceId", s.get("traceId"));
            line.put("spanId", s.get("spanId"));
            line.put("runId", s.get("runId"));
            line.put("eventId", s.get("eventId"));
            lines.add(line);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("records", lines);
        r.put("total", lines.size());
        r.put("source", lines.isEmpty() ? "empty" : "gov_obs_span");
        r.put("hint", "P0 日志检索回落 span；无独立 log 表");
        return r;
    }

    @Override
    public Map<String, Object> spanError(String spanId, String ws) {
        String workspace = vip.xiaonuo.lh.core.ws.LhWsFilters.listWs(ws);
        GovObsSpan span = spanMapper.selectOne(new QueryWrapper<GovObsSpan>().lambda()
                .eq(GovObsSpan::getDeleteFlag, NOT_DELETE)
                .eq(StrUtil.isNotBlank(workspace), GovObsSpan::getWs, workspace)
                .and(w -> w.eq(GovObsSpan::getSpanId, spanId).or().eq(GovObsSpan::getId, spanId))
                .last("LIMIT 1"));
        Map<String, Object> r = new LinkedHashMap<>();
        if (span == null) {
            r.put("found", false);
            r.put("neighbors", List.of());
            r.put("source", "empty");
            return r;
        }
        r.put("found", true);
        r.put("span", toSpanRow(span));
        r.put("error", span.getError());
        List<GovObsSpan> neighbors = spanMapper.selectList(new QueryWrapper<GovObsSpan>().lambda()
                .eq(GovObsSpan::getDeleteFlag, NOT_DELETE)
                .eq(StrUtil.isNotBlank(workspace), GovObsSpan::getWs, workspace)
                .eq(GovObsSpan::getTraceId, span.getTraceId())
                .ne(GovObsSpan::getId, span.getId())
                .orderByAsc(GovObsSpan::getStartTs)
                .last("LIMIT 20"));
        List<Map<String, Object>> ns = new ArrayList<>();
        for (GovObsSpan n : neighbors) {
            ns.add(toSpanRow(n));
        }
        r.put("neighbors", ns);
        r.put("source", "gov_obs_span");
        return r;
    }

    private static Map<String, Object> toSpanRow(GovObsSpan s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("traceId", s.getTraceId());
        m.put("spanId", s.getSpanId());
        m.put("parentSpanId", s.getParentSpanId());
        m.put("linkId", s.getLinkId());
        m.put("service", s.getService());
        m.put("op", s.getOp());
        m.put("status", s.getStatus());
        m.put("startTs", s.getStartTs());
        m.put("endTs", s.getEndTs());
        m.put("durationMs", s.getDurationMs());
        m.put("runId", s.getRunId());
        m.put("eventId", s.getEventId());
        m.put("error", s.getError());
        return m;
    }

    private static Long percentile(List<GovObsSpan> list, double p) {
        List<Long> durs = new ArrayList<>();
        for (GovObsSpan s : list) {
            if (s.getDurationMs() != null) {
                durs.add(s.getDurationMs());
            }
        }
        if (durs.isEmpty()) {
            return 0L;
        }
        durs.sort(Long::compareTo);
        int idx = Math.min(durs.size() - 1, Math.max(0, (int) Math.ceil(p * durs.size()) - 1));
        return durs.get(idx);
    }

    private static double errorRate(List<GovObsSpan> list) {
        if (list.isEmpty()) {
            return 0d;
        }
        long err = list.stream().filter(s -> "error".equalsIgnoreCase(s.getStatus())).count();
        return Math.round(err * 10000.0 / list.size()) / 100.0;
    }

    private static Date startOfDay() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTime();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
