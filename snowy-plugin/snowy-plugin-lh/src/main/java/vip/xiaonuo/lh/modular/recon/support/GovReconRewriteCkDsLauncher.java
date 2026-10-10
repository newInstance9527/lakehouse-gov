package vip.xiaonuo.lh.modular.recon.support;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.engine.DsClient;
import vip.xiaonuo.lh.core.engine.SparkSubmitBuilder;
import vip.xiaonuo.lh.modular.metric.support.MetricMaterializeJobTemplate;
import vip.xiaonuo.lh.modular.metric.support.MetricMaterializeRewrite;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 黄金重导 CK：投影 Spark SQL SHELL 到 DolphinScheduler，以湖表分区覆盖热表。
 */
@Component
public class GovReconRewriteCkDsLauncher {

    private static final Logger log = LoggerFactory.getLogger(GovReconRewriteCkDsLauncher.class);

    @Resource
    private DsClient dsClient;
    @Resource
    private LhProperties lhProperties;

    public LaunchResult launch(
            String lakeTable,
            String ckTable,
            String metricCode,
            String partitionDt,
            String runId,
            String traceId) {
        String bare = bareTable(StrUtil.blankToDefault(ckTable, lakeTable));
        String wfName = workflowName(bare);
        String dt = StrUtil.blankToDefault(partitionDt, java.time.LocalDate.now().toString());
        String lake = normalizeLake(lakeTable);
        String ck = MetricMaterializeRewrite.assertSafeTable(
                StrUtil.blankToDefault(ckTable, bareTable(lake)));
        String select = "SELECT * FROM " + lake + " WHERE dt = DATE '" + dt + "'";
        String code = MetricMaterializeJobTemplate.safeCode(
                StrUtil.blankToDefault(metricCode, "REWRITE_" + bare.toUpperCase(Locale.ROOT)));
        String sql = MetricMaterializeJobTemplate.clickHouseInsertSql(code, null, ck, select, dt);
        int timeout = timeoutMinutes();

        Map<String, Object> task = sparkTask("rewrite_ck", "重导 CK " + bare, sql, timeout,
                code, ck, dt, lake, traceId);
        return submit(wfName, List.of(task), runId, code, lake, ck, dt, sql, timeout, traceId);
    }

    private LaunchResult submit(
            String processName,
            List<Map<String, Object>> tasks,
            String runId,
            String metricCode,
            String lakeTable,
            String ckTable,
            String partitionDt,
            String sqlPreview,
            int timeoutMinutes,
            String traceId) {

        String callbackUrl = callbackUrl();
        List<Map<String, Object>> submitTasks = new ArrayList<>(tasks);
        List<Map<String, Object>> submitRels = new ArrayList<>();
        if (StrUtil.isNotBlank(callbackUrl) && StrUtil.isNotBlank(callbackToken()) && !submitTasks.isEmpty()) {
            String lastKey = str(submitTasks.get(submitTasks.size() - 1).get("nodeKey"), null);
            if (StrUtil.isNotBlank(lastKey)) {
                submitTasks.add(notifyShellTask(runId, callbackUrl, callbackToken(), List.of(lastKey)));
                submitRels.add(Map.of("from", lastKey, "to", "notify_portal"));
            }
        }

        Map<String, Object> workflow = new LinkedHashMap<>();
        workflow.put("workflowCode", processName);
        workflow.put("name", processName);
        workflow.put("description", "lakehouse recon rewrite_ck lake=" + lakeTable
                + " ck=" + ckTable + " run=" + runId + " job_principal=" + jobPrincipal());
        workflow.put("trial", true);
        workflow.put("executionType", "PARALLEL");
        workflow.put("tasks", submitTasks);
        workflow.put("taskRelation", submitRels);
        workflow.put("taskCount", submitTasks.size());
        workflow.put("edgeCount", submitRels.size());

        Map<String, Object> create = dsClient.createOrUpdateWorkflow(workflow);
        String wfCode = StrUtil.blankToDefault(String.valueOf(create.get("workflowCode")), processName);
        boolean createDegraded = Boolean.TRUE.equals(create.get("degraded"))
                || Boolean.FALSE.equals(create.get("ok"));

        Map<String, Object> startParams = new LinkedHashMap<>();
        startParams.put("run_id", runId);
        startParams.put("kind", "rewrite_ck");
        startParams.put("metric_code", metricCode);
        startParams.put("job_principal", jobPrincipal());
        startParams.put("lake_table", lakeTable);
        startParams.put("ck_table", ckTable);
        startParams.put("partition_dt", partitionDt);
        if (StrUtil.isNotBlank(traceId)) {
            startParams.put("trace_id", traceId);
        }
        if (StrUtil.isNotBlank(callbackUrl)) {
            startParams.put("callback_url", callbackUrl);
            startParams.put("callback_enabled", "true");
        }

        Map<String, Object> start = Map.of();
        String instanceId = null;
        boolean startDegraded = true;
        if (!createDegraded && StrUtil.isNotBlank(wfCode)) {
            start = dsClient.startProcessInstance(wfCode, startParams);
            startDegraded = Boolean.TRUE.equals(start.get("degraded"));
            instanceId = str(start.get("processInstanceId"), null);
        }

        boolean degraded = createDegraded || startDegraded || StrUtil.isBlank(instanceId);
        boolean allowDegraded = lhProperties.getRecon() == null
                || lhProperties.getRecon().isAllowDegradedRewriteCk();

        LaunchResult r = new LaunchResult();
        r.workflowCode = wfCode;
        r.processInstanceId = instanceId;
        r.degraded = degraded;
        r.sql = sqlPreview;
        r.createResp = create;
        r.startResp = start;
        r.jobPrincipal = jobPrincipal();
        r.callbackAttached = !submitRels.isEmpty();
        r.jobRef = StrUtil.isNotBlank(instanceId) ? instanceId
                : (StrUtil.isNotBlank(wfCode) ? "wf:" + wfCode : processName);
        r.message = buildMessage(create, start, createDegraded, startDegraded, instanceId);
        r.ok = !degraded || allowDegraded;
        if (degraded) {
            log.warn("recon rewrite_ck DS launch degraded lake={} ck={} msg={}", lakeTable, ckTable, r.message);
        }
        return r;
    }

    private Map<String, Object> notifyShellTask(
            String runId, String callbackUrl, String token, List<String> preTasks) {
        String safeRun = shellSingleQuote(runId);
        String safeUrl = shellSingleQuote(callbackUrl);
        String safeToken = shellSingleQuote(token);
        String script = ""
                + "#!/bin/bash\n"
                + "set -euo pipefail\n"
                + "RUN_ID='" + safeRun + "'\n"
                + "URL='" + safeUrl + "'\n"
                + "TOKEN='" + safeToken + "'\n"
                + "BODY=$(printf '{\"runId\":\"%s\",\"eventId\":\"%s\",\"status\":\"success\",\"source\":\"ds_notify\"}' \"$RUN_ID\" \"$RUN_ID\")\n"
                + "curl -sS -m 30 -X POST \"$URL\" \\\n"
                + "  -H 'Content-Type: application/json' \\\n"
                + "  -H \"" + GovReconCallbackAuth.HEADER + ": $TOKEN\" \\\n"
                + "  -d \"$BODY\" || echo \"recon callback soft-fail\"\n";
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("localParams", List.of());
        params.put("resourceList", List.of());
        params.put("rawScript", script);
        params.put("lhPreferShell", true);

        Map<String, Object> task = new LinkedHashMap<>();
        task.put("nodeKey", "notify_portal");
        task.put("name", "通知门户黄金回调");
        task.put("engine", "shell");
        task.put("taskType", "SHELL");
        task.put("description", "POST /lh/recon/golden/callback");
        task.put("failRetryTimes", 2);
        task.put("failRetryInterval", 5);
        task.put("timeout", 5);
        task.put("timeoutFlag", "OPEN");
        task.put("timeoutNotifyStrategy", "FAILED");
        task.put("preTasks", preTasks == null ? List.of() : preTasks);
        task.put("taskParams", params);
        task.put("x", 420);
        task.put("y", 180);
        return task;
    }

    private String callbackUrl() {
        LhProperties.Recon r = lhProperties.getRecon();
        if (r == null || StrUtil.isBlank(r.getCallbackBaseUrl())) {
            return null;
        }
        String base = r.getCallbackBaseUrl().trim().replaceAll("/+$", "");
        return base + "/lh/recon/golden/callback";
    }

    private String callbackToken() {
        LhProperties.Recon r = lhProperties.getRecon();
        if (r == null || StrUtil.isBlank(r.getCallbackToken())) {
            return null;
        }
        return r.getCallbackToken().trim();
    }

    private static String shellSingleQuote(String raw) {
        return StrUtil.blankToDefault(raw, "").replace("'", "'\"'\"'");
    }

    private Map<String, Object> sparkTask(
            String nodeKey, String name, String sql, int timeoutMin,
            String metricCode, String ckTable, String partitionDt, String lakeTable, String traceId) {
        String master = lhProperties.getSpark() != null
                ? StrUtil.blankToDefault(lhProperties.getSpark().getMaster(), "local[*]")
                : "local[*]";
        String shell = SparkSubmitBuilder.buildSqlShell(nodeKey, sql, master);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("localParams", List.of());
        params.put("resourceList", List.of());
        params.put("lhSql", sql);
        params.put("sql", sql);
        params.put("lhSparkSql", sql);
        params.put("master", master);
        params.put("lhPreferShell", true);
        params.put("rawScript", shell);
        params.put("metric_code", metricCode);
        params.put("engine", "clickhouse");
        params.put("target_table", ckTable);
        params.put("lake_table", lakeTable);
        params.put("partition_dt", partitionDt);
        if (StrUtil.isNotBlank(traceId)) {
            params.put("trace_id", traceId);
        }

        Map<String, Object> task = new LinkedHashMap<>();
        task.put("nodeKey", nodeKey);
        task.put("name", name);
        task.put("engine", "spark");
        task.put("taskType", "SPARK");
        task.put("description", "recon rewrite_ck metric_code=" + metricCode);
        task.put("failRetryTimes", 1);
        task.put("failRetryInterval", 3);
        task.put("timeout", Math.max(10, timeoutMin));
        task.put("timeoutFlag", "OPEN");
        task.put("timeoutNotifyStrategy", "FAILED");
        task.put("preTasks", List.of());
        task.put("taskParams", params);
        task.put("x", 120);
        task.put("y", 180);
        return task;
    }

    private String workflowName(String bareTable) {
        String tpl = lhProperties.getRecon() != null
                ? lhProperties.getRecon().getRewriteCkWorkflowTemplate() : null;
        String pattern = StrUtil.blankToDefault(tpl, "job.reconcile.{table}.rewrite_ck");
        return pattern.replace("{table}", bareTable.toLowerCase(Locale.ROOT));
    }

    private String jobPrincipal() {
        String p = lhProperties.getRecon() != null ? lhProperties.getRecon().getJobPrincipal() : null;
        if (StrUtil.isNotBlank(p)) {
            return p.trim();
        }
        return "job.ads_ck_loader";
    }

    private int timeoutMinutes() {
        Integer t = lhProperties.getRecon() != null ? lhProperties.getRecon().getRewriteTimeoutMinutes() : null;
        return t != null && t > 0 ? t : 60;
    }

    private static String normalizeLake(String lakeTable) {
        String t = StrUtil.blankToDefault(lakeTable, "").trim();
        if (StrUtil.isBlank(t)) {
            throw new IllegalArgumentException("lakeTable 不能为空");
        }
        if (!t.contains(".")) {
            t = "iceberg.ads." + t;
        } else if (t.chars().filter(ch -> ch == '.').count() == 1
                && !t.toLowerCase(Locale.ROOT).startsWith("iceberg.")) {
            t = "iceberg." + t;
        }
        return MetricMaterializeRewrite.assertSafeTable(t);
    }

    private static String bareTable(String fqn) {
        if (StrUtil.isBlank(fqn)) {
            return "unknown";
        }
        String t = fqn.trim();
        int dot = t.lastIndexOf('.');
        return dot >= 0 ? t.substring(dot + 1) : t;
    }

    private static String buildMessage(
            Map<String, Object> create, Map<String, Object> start,
            boolean createDegraded, boolean startDegraded, String instanceId) {
        List<String> parts = new ArrayList<>();
        if (createDegraded) {
            parts.add("DS 工作流投影降级: " + str(create.get("message"), "create failed"));
        }
        if (startDegraded || StrUtil.isBlank(instanceId)) {
            parts.add("DS 启动降级: " + str(start.get("message"), "start failed"));
        }
        if (parts.isEmpty()) {
            return "ok instance=" + instanceId;
        }
        return String.join("; ", parts);
    }

    private static String str(Object o, String def) {
        if (o == null) {
            return def;
        }
        String s = String.valueOf(o);
        return "null".equalsIgnoreCase(s) ? def : s;
    }

    public static class LaunchResult {
        public boolean ok;
        public boolean degraded;
        public boolean callbackAttached;
        public String workflowCode;
        public String processInstanceId;
        public String jobRef;
        public String sql;
        public String message;
        public String jobPrincipal;
        public Map<String, Object> createResp;
        public Map<String, Object> startResp;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", ok);
            m.put("degraded", degraded);
            m.put("callbackAttached", callbackAttached);
            m.put("workflowCode", workflowCode);
            m.put("processInstanceId", processInstanceId);
            m.put("jobRef", jobRef);
            m.put("sql", sql);
            m.put("message", message);
            m.put("jobPrincipal", jobPrincipal);
            return m;
        }
    }
}
