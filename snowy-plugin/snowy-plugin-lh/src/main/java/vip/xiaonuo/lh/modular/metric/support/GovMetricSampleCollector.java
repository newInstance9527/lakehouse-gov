package vip.xiaonuo.lh.modular.metric.support;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.engine.VictoriaMetricsClient;
import vip.xiaonuo.lh.modular.metric.entity.GovMetric;
import vip.xiaonuo.lh.modular.metric.entity.GovMetricSample;
import vip.xiaonuo.lh.modular.metric.mapper.GovMetricMapper;
import vip.xiaonuo.lh.modular.metric.mapper.GovMetricSampleMapper;
import vip.xiaonuo.lh.modular.metric.service.GovMetricExecService;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日波动采样：对 active 指标以 JOB 身份跑 query，落 {@code gov_metric_sample}，
 * 并写 VM {@code lh_metric_*}（未配 vm-import-url 则跳过）。
 */
@Component
public class GovMetricSampleCollector {

    private static final Logger log = LoggerFactory.getLogger(GovMetricSampleCollector.class);
    private static final String NOT_DELETE = "NOT_DELETE";
    private static final String WS_DEFAULT = "default";

    @Resource
    private GovMetricMapper metricMapper;
    @Resource
    private GovMetricSampleMapper sampleMapper;
    @Resource
    private GovMetricExecService execService;
    @Resource
    private VictoriaMetricsClient victoriaMetricsClient;
    @Resource
    private LhProperties lhProperties;

    public Map<String, Object> runDaily(String ws) {
        String workspace = StrUtil.blankToDefault(ws, WS_DEFAULT).trim();
        LocalDate sampleDay = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        BigDecimal threshold = thresholdPct();
        int max = maxMetrics();
        List<GovMetric> metrics = listActive(workspace, max);
        int ok = 0;
        int failed = 0;
        int anomalyCount = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> metricLines = new ArrayList<>();
        long dayTs = sampleDay.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();

        for (GovMetric head : metrics) {
            Map<String, Object> one = sampleOne(head, sampleDay, threshold, dayTs, metricLines);
            rows.add(one);
            String st = String.valueOf(one.get("status"));
            if ("ok".equals(st)) {
                ok++;
            } else {
                failed++;
            }
            if (Boolean.TRUE.equals(one.get("anomaly"))) {
                anomalyCount++;
            }
        }

        Map<String, Object> vm = victoriaMetricsClient.importPrometheus(
                GovMetricMetricsFormatter.joinBody(metricLines));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ws", workspace);
        out.put("sampleDt", sampleDay.toString());
        out.put("thresholdPct", threshold);
        out.put("total", metrics.size());
        out.put("ok", ok);
        out.put("failed", failed);
        out.put("anomalyCount", anomalyCount);
        out.put("vm", vm);
        out.put("rows", rows);
        out.put("at", Instant.now().toString());
        return out;
    }

    private Map<String, Object> sampleOne(GovMetric head, LocalDate sampleDay, BigDecimal threshold,
                                          long dayTs, List<String> metricLines) {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("metricCode", head.getMetricCode());
        one.put("ws", head.getWs());
        one.put("sampleDt", sampleDay.toString());

        Map<String, Object> params = Map.of("dt", sampleDay.toString());
        Map<String, Object> exec;
        try {
            exec = execService.sampleQuery(head.getMetricCode(), head.getWs(), params, 50);
        } catch (Exception e) {
            persistFailed(head, sampleDay, threshold, null, "failed",
                    StrUtil.maxLength(e.getMessage(), 500));
            one.put("status", "failed");
            one.put("message", e.getMessage());
            one.put("anomaly", false);
            return one;
        }

        boolean degraded = Boolean.TRUE.equals(exec.get("degraded"))
                || "failed".equals(String.valueOf(exec.get("status")))
                || "blocked".equals(String.valueOf(exec.get("status")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dataRows = (List<Map<String, Object>>) exec.getOrDefault("rows", List.of());
        @SuppressWarnings("unchecked")
        List<String> columns = (List<String>) exec.getOrDefault("columns", List.of());
        BigDecimal value = MetricAnomalyCalc.extractScalar(dataRows, columns);
        String queryId = str(exec.get("queryId"));
        String ver = str(exec.get("ver"));
        if (StrUtil.isBlank(ver)) {
            ver = head.getCurrentVer();
        }

        if (degraded || value == null) {
            String msg = StrUtil.blankToDefault(str(exec.get("message")),
                    value == null ? "无标量结果" : "执行降级");
            String status = degraded ? "degraded" : "failed";
            persistFailed(head, sampleDay, threshold, queryId, status, msg);
            one.put("status", status);
            one.put("message", msg);
            one.put("anomaly", false);
            one.put("queryId", queryId);
            return one;
        }

        GovMetricSample prev = findPrev(head.getWs(), head.getMetricCode(), sampleDay);
        BigDecimal prevVal = prev == null ? null : prev.getMetricValue();
        BigDecimal change = MetricAnomalyCalc.changePct(value, prevVal);
        boolean anomaly = MetricAnomalyCalc.isAnomaly(change, threshold);

        upsertOk(head, sampleDay, ver, value, prevVal, change, anomaly, threshold, queryId);

        if (victoriaMetricsClient.configured()) {
            metricLines.addAll(GovMetricMetricsFormatter.format(
                    head.getMetricCode(),
                    StrUtil.blankToDefault(head.getWs(), WS_DEFAULT),
                    ver,
                    value.doubleValue(),
                    change == null ? null : change.doubleValue(),
                    anomaly,
                    dayTs));
        }

        one.put("status", "ok");
        one.put("ver", ver);
        one.put("metricValue", value);
        one.put("prevValue", prevVal);
        one.put("changePct", change);
        one.put("anomaly", anomaly);
        one.put("queryId", queryId);
        one.put("severity", MetricAnomalyCalc.severity(change, threshold));
        return one;
    }

    private void upsertOk(GovMetric head, LocalDate sampleDay, String ver,
                          BigDecimal value, BigDecimal prevVal, BigDecimal change,
                          boolean anomaly, BigDecimal threshold, String queryId) {
        Date dt = java.sql.Date.valueOf(sampleDay);
        GovMetricSample existing = findExact(head.getWs(), head.getMetricCode(), sampleDay);
        Date now = new Date();
        boolean insert = existing == null;
        if (insert) {
            existing = new GovMetricSample();
            existing.setId(IdUtil.getSnowflakeNextIdStr());
            existing.setCreateTime(now);
        }
        existing.setWs(StrUtil.blankToDefault(head.getWs(), WS_DEFAULT));
        existing.setMetricCode(head.getMetricCode());
        existing.setVer(ver);
        existing.setSampleDt(dt);
        existing.setMetricValue(value);
        existing.setPrevValue(prevVal);
        existing.setChangePct(change);
        existing.setAnomaly(anomaly ? 1 : 0);
        existing.setThresholdPct(threshold);
        existing.setStatus("ok");
        existing.setMessage(MetricAnomalyCalc.summarize(anomaly, change, threshold));
        existing.setQueryId(queryId);
        if (insert) {
            sampleMapper.insert(existing);
        } else {
            sampleMapper.updateById(existing);
        }
    }

    private void persistFailed(GovMetric head, LocalDate sampleDay, BigDecimal threshold,
                               String queryId, String status, String message) {
        Date dt = java.sql.Date.valueOf(sampleDay);
        GovMetricSample existing = findExact(head.getWs(), head.getMetricCode(), sampleDay);
        Date now = new Date();
        if (existing == null) {
            existing = new GovMetricSample();
            existing.setId(IdUtil.getSnowflakeNextIdStr());
            existing.setCreateTime(now);
            existing.setWs(StrUtil.blankToDefault(head.getWs(), WS_DEFAULT));
            existing.setMetricCode(head.getMetricCode());
            existing.setSampleDt(dt);
            existing.setThresholdPct(threshold);
            existing.setAnomaly(0);
            existing.setStatus(status);
            existing.setMessage(message);
            existing.setQueryId(queryId);
            existing.setVer(head.getCurrentVer());
            sampleMapper.insert(existing);
            return;
        }
        existing.setStatus(status);
        existing.setMessage(message);
        existing.setQueryId(queryId);
        existing.setThresholdPct(threshold);
        existing.setAnomaly(0);
        sampleMapper.updateById(existing);
    }

    private GovMetricSample findExact(String ws, String code, LocalDate sampleDay) {
        return sampleMapper.selectOne(new QueryWrapper<GovMetricSample>().lambda()
                .eq(GovMetricSample::getWs, StrUtil.blankToDefault(ws, WS_DEFAULT))
                .eq(GovMetricSample::getMetricCode, code)
                .eq(GovMetricSample::getSampleDt, java.sql.Date.valueOf(sampleDay))
                .last("LIMIT 1"));
    }

    private GovMetricSample findPrev(String ws, String code, LocalDate sampleDay) {
        return sampleMapper.selectOne(new QueryWrapper<GovMetricSample>().lambda()
                .eq(GovMetricSample::getWs, StrUtil.blankToDefault(ws, WS_DEFAULT))
                .eq(GovMetricSample::getMetricCode, code)
                .lt(GovMetricSample::getSampleDt, java.sql.Date.valueOf(sampleDay))
                .eq(GovMetricSample::getStatus, "ok")
                .orderByDesc(GovMetricSample::getSampleDt)
                .last("LIMIT 1"));
    }

    private List<GovMetric> listActive(String ws, int max) {
        QueryWrapper<GovMetric> qw = new QueryWrapper<>();
        qw.lambda().eq(GovMetric::getDeleteFlag, NOT_DELETE)
                .eq(GovMetric::getStatus, "active")
                .eq(GovMetric::getWs, ws)
                .orderByAsc(GovMetric::getMetricCode)
                .last("LIMIT " + Math.max(1, max));
        return metricMapper.selectList(qw);
    }

    private BigDecimal thresholdPct() {
        LhProperties.Metric m = lhProperties.getMetric();
        double v = m == null ? 20d : m.getAnomalyThresholdPct();
        return BigDecimal.valueOf(v);
    }

    private int maxMetrics() {
        LhProperties.Metric m = lhProperties.getMetric();
        return m == null ? 50 : Math.max(1, m.getSampleMaxMetrics());
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
