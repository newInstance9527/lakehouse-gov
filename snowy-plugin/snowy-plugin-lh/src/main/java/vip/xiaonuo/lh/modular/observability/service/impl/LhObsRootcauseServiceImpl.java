package vip.xiaonuo.lh.modular.observability.service.impl;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.modular.etl.param.IgEtlBackfillParam;
import vip.xiaonuo.lh.modular.etl.service.IgEtlService;
import vip.xiaonuo.lh.modular.observability.service.LhObsRootcauseService;
import vip.xiaonuo.lh.modular.observability.service.LhObsSpanService;
import vip.xiaonuo.lh.modular.recon.support.ReconPartitionService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LhObsRootcauseServiceImpl implements LhObsRootcauseService {

    @Resource
    private LhObsSpanService lhObsSpanService;
    @Resource
    private ReconPartitionService reconPartitionService;
    @Resource
    private IgEtlService igEtlService;

    @Override
    public Map<String, Object> alerts(String ws) {
        List<Map<String, Object>> records = new ArrayList<>();
        Map<String, Object> recon = reconPartitionService.listRecent(null, "fail", 20);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fails = (List<Map<String, Object>>) recon.getOrDefault("records", List.of());
        for (Map<String, Object> r : fails) {
            Map<String, Object> opt = new LinkedHashMap<>();
            String id = "recon:" + r.get("id");
            opt.put("value", id);
            opt.put("label", StrUtil.blankToDefault(str(r.get("ckTable")), str(r.get("lakeTable")))
                    + " · " + r.get("partitionKey"));
            opt.put("kind", "recon_fail");
            opt.put("metricCode", r.get("metricCode"));
            opt.put("traceId", r.get("traceId"));
            records.add(opt);
        }
        Map<String, Object> spanPage = lhObsSpanService.listSpans(ws, null, null, null, null, "error", 1, 20);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> spans = (List<Map<String, Object>>) spanPage.getOrDefault("records", List.of());
        for (Map<String, Object> s : spans) {
            Map<String, Object> opt = new LinkedHashMap<>();
            opt.put("value", "span:" + s.get("spanId"));
            opt.put("label", s.get("service") + " " + s.get("op") + " · " + s.get("runId"));
            opt.put("kind", "span_error");
            opt.put("traceId", s.get("traceId"));
            opt.put("runId", s.get("runId"));
            records.add(opt);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("source", records.isEmpty() ? "empty" : "recon+span");
        r.put("ws", blankToNull(ws));
        r.put("records", records);
        r.put("hint", records.isEmpty()
                ? "无活跃告警/对账失败焦点时为空列表；禁止演示故事线"
                : "来自 recon_partition fail + gov_obs_span error");
        return r;
    }

    @Override
    public Map<String, Object> analyze(Map<String, Object> body) {
        String alertId = body == null ? null : str(body.get("alertId"));
        String table = body == null ? null : str(body.get("table"));
        String job = body == null ? null : str(body.get("job"));
        String ws = body == null ? null : str(body.get("ws"));
        String runId = body == null ? null : str(body.get("runId"));
        String traceId = body == null ? null : str(body.get("traceId"));

        List<Map<String, Object>> steps = new ArrayList<>();
        List<Map<String, Object>> evidence = new ArrayList<>();
        List<Map<String, Object>> actions = new ArrayList<>();

        if (StrUtil.isNotBlank(alertId) && alertId.startsWith("recon:")) {
            steps.add(step(1, "对账失败焦点", "done", "命中 recon_partition fail"));
            evidence.add(ev("recon", alertId, "/reliability", null));
            actions.add(act("remediate_rewrite", "重导 CK / 补数", "remediate"));
        }
        if (StrUtil.isNotBlank(alertId) && alertId.startsWith("span:")) {
            String spanId = alertId.substring("span:".length());
            Map<String, Object> err = lhObsSpanService.spanError(spanId, ws);
            steps.add(step(1, "失败 span", "done", "gov_obs_span"));
            if (Boolean.TRUE.equals(err.get("found"))) {
                evidence.add(ev("span", spanId, "/linktrace", Map.of("spanId", spanId)));
                @SuppressWarnings("unchecked")
                Map<String, Object> span = (Map<String, Object>) err.get("span");
                if (span != null && span.get("runId") != null) {
                    runId = str(span.get("runId"));
                    evidence.add(ev("run", runId, "/ops", Map.of("runId", runId)));
                }
            }
            actions.add(act("remediate_backfill", "补数重跑", "remediate"));
        }
        if (StrUtil.isNotBlank(runId)) {
            steps.add(step(2, "任务运行", "done", "runId=" + runId));
            evidence.add(ev("run", runId, "/ops", Map.of("runId", runId)));
        }
        if (StrUtil.isNotBlank(traceId)) {
            steps.add(step(3, "链路瀑布", "done", "traceId=" + traceId));
            evidence.add(ev("trace", traceId, "/linktrace", Map.of("traceId", traceId)));
        }
        if (StrUtil.isNotBlank(table)) {
            steps.add(step(4, "表焦点", "done", table));
            evidence.add(ev("table", table, "/lineage", Map.of("table", table)));
        }
        if (StrUtil.isNotBlank(job)) {
            steps.add(step(5, "作业焦点", "done", job));
            evidence.add(ev("job", job, "/integration", Map.of("dagId", job)));
        }

        boolean empty = steps.isEmpty() && evidence.isEmpty();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("source", empty ? "empty" : "recon+span+etl");
        r.put("alertId", blankToNull(alertId));
        r.put("table", blankToNull(table));
        r.put("job", blankToNull(job));
        r.put("runId", blankToNull(runId));
        r.put("traceId", blankToNull(traceId));
        r.put("steps", steps);
        r.put("evidence", evidence);
        r.put("actions", actions);
        r.put("longTerm", empty ? List.of() : List.of(
                Map.of("text", "接通夜莺告警后自动填充焦点", "href", "/infra")));
        r.put("aiDiagnosePath", "/lh/ai/chat");
        r.put("aiDiagnoseHint", "intent=diagnose；门户可带 table/job 调用 AI 助手");
        r.put("message", empty
                ? "暂无血缘×任务×质量×span 可叠加证据；接通采集与告警后返回真实编排（禁止演示卡片）"
                : "已叠加可用证据；结论请走 /rootcause/conclusion，处置走 /rootcause/remediate");
        return r;
    }

    @Override
    public Map<String, Object> conclusion(Map<String, Object> body) {
        Map<String, Object> analyzed = analyze(body == null ? Map.of() : body);
        String summary = body == null ? null : str(body.get("summary"));
        String table = body == null ? null : str(body.get("table"));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("analyze", analyzed);
        r.put("summary", StrUtil.blankToDefault(summary,
                StrUtil.blankToDefault(str(analyzed.get("message")), "无证据，未生成结论")));
        r.put("ai", Map.of(
                "intent", "diagnose",
                "path", "/lh/ai/chat",
                "promptHint", StrUtil.isBlank(table)
                        ? "请诊断近期对账/任务失败根因"
                        : ("请诊断表 " + table + " 的数据异常根因")));
        r.put("source", analyzed.get("source"));
        return r;
    }

    @Override
    public Map<String, Object> remediate(Map<String, Object> body) {
        if (body == null) {
            throw new CommonException("body 必填");
        }
        String dagId = str(body.get("dagId"));
        if (StrUtil.isBlank(dagId)) {
            dagId = str(body.get("job"));
        }
        String markKey = StrUtil.blankToDefault(str(body.get("markKey")), "dt");
        String markValue = str(body.get("markValue"));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        if (StrUtil.isNotBlank(dagId) && StrUtil.isNotBlank(markValue)) {
            IgEtlBackfillParam p = new IgEtlBackfillParam();
            p.setId(dagId);
            p.setMarkKey(markKey);
            p.setMarkValue(markValue);
            p.setEnv(str(body.get("env")));
            p.setConfirmReqNo(str(body.get("confirmReqNo")));
            r.put("backfill", igEtlService.backfill(p));
        } else {
            r.put("backfill", null);
            r.put("backfillHint", "须提供 dagId/job + markValue 才触发 ETL 补数");
        }
        r.put("notifyOwner", Map.of(
                "status", "queued",
                "hint", "通知 owner：对接站内信/webhook 后投递"));
        r.put("ticket", Map.of(
                "type", "remediate",
                "status", "draft",
                "href", "/apply",
                "hint", "可在申请中心创建治理/补数工单"));
        r.put("source", r.get("backfill") == null ? "partial" : "etl");
        return r;
    }

    private static Map<String, Object> step(int n, String title, String status, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("n", n);
        m.put("title", title);
        m.put("status", status);
        m.put("detail", detail);
        return m;
    }

    private static Map<String, Object> ev(String kind, String id, String path, Map<String, Object> query) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("id", id);
        m.put("action", Map.of("type", "route", "path", path, "query", query == null ? Map.of() : query));
        return m;
    }

    private static Map<String, Object> act(String id, String label, String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("label", label);
        m.put("type", type);
        return m;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String blankToNull(String s) {
        return StrUtil.isBlank(s) ? null : s.trim();
    }
}
