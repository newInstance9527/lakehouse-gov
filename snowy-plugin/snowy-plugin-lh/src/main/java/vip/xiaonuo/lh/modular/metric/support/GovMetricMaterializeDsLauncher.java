package vip.xiaonuo.lh.modular.metric.support;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.engine.DsClient;
import vip.xiaonuo.lh.core.engine.SparkSubmitBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 指标物化 → DolphinScheduler：投影 Spark SQL SHELL，工作流名绑定 metric_code。
 */
@Component
public class GovMetricMaterializeDsLauncher {

    private static final Logger log = LoggerFactory.getLogger(GovMetricMaterializeDsLauncher.class);

    @Resource
    private DsClient dsClient;
    @Resource
    private LhProperties lhProperties;

    public LaunchResult launchMaterialize(
            String metricCode,
            String ver,
            String engine,
            String targetTable,
            String sql,
            String runId,
            String partitionDt) {
        String code = MetricMaterializeJobTemplate.safeCode(metricCode);
        String eng = MetricMaterializeRewrite.normalizeEngine(engine);
        String wfName = MetricMaterializeJobTemplate.workflowName(code);
        String nodeKey = "mat_" + eng;
        int timeout = timeoutMinutes();
        String body = StrUtil.blankToDefault(sql, "SELECT 1 AS _lh_metric_mat_empty");
        List<Map<String, Object>> tasks = List.of(
                sparkTask(nodeKey, "物化 " + code + " → " + eng, body, timeout, List.of(), code, ver, eng, targetTable, partitionDt));
        return submit(wfName, tasks, List.of(), runId, "materialize", code, ver, eng, targetTable, body, timeout, null);
    }

    public LaunchResult launchRecon(
            String metricCode,
            String lakeTable,
            String ckTable,
            String partitionDt,
            String runId) {
        String code = MetricMaterializeJobTemplate.safeCode(metricCode);
        String wfName = MetricMaterializeJobTemplate.reconWorkflowName(code);
        String sql = MetricMaterializeJobTemplate.reconCountSql(
                StrUtil.blankToDefault(lakeTable, "iceberg.ads.metric_placeholder"),
                StrUtil.blankToDefault(ckTable, "ads.metric_placeholder"),
                partitionDt);
        int timeout = Math.min(timeoutMinutes(), 30);
        List<Map<String, Object>> tasks = List.of(
                sparkTask("recon_part", "分区对账 " + code, sql, timeout, List.of(),
                        code, null, "recon", ckTable, partitionDt));
        return submit(wfName, tasks, List.of(), runId, "recon", code, null, "recon", ckTable, sql, timeout, "0 30 7 * * ?");
    }

    private LaunchResult submit(
            String processName,
            List<Map<String, Object>> tasks,
            List<Map<String, Object>> relations,
            String runId,
            String kind,
            String metricCode,
            String ver,
            String engine,
            String targetTable,
            String sqlPreview,
            int timeoutMinutes,
            String cron) {

        Map<String, Object> workflow = new LinkedHashMap<>();
        workflow.put("workflowCode", processName);
        workflow.put("name", processName);
        workflow.put("description", "lakehouse metric " + kind + " metric_code=" + metricCode
                + " run=" + runId + " job_principal=" + jobPrincipal());
        workflow.put("trial", true);
        workflow.put("executionType", "PARALLEL");
        workflow.put("tasks", tasks);
        workflow.put("taskRelation", relations);
        workflow.put("taskCount", tasks.size());
        workflow.put("edgeCount", relations.size());
        if (StrUtil.isNotBlank(cron)) {
            workflow.put("cron", cron);
        }

        Map<String, Object> create = dsClient.createOrUpdateWorkflow(workflow);
        String wfCode = StrUtil.blankToDefault(String.valueOf(create.get("workflowCode")), processName);
        boolean createDegraded = Boolean.TRUE.equals(create.get("degraded"))
                || Boolean.FALSE.equals(create.get("ok"));

        Map<String, Object> startParams = new LinkedHashMap<>();
        startParams.put("run_id", runId);
        startParams.put("kind", kind);
        startParams.put("metric_code", metricCode);
        startParams.put("job_principal", jobPrincipal());
        if (StrUtil.isNotBlank(ver)) {
            startParams.put("ver", ver);
        }
        if (StrUtil.isNotBlank(engine)) {
            startParams.put("engine", engine);
        }
        if (StrUtil.isNotBlank(targetTable)) {
            startParams.put("target_table", targetTable);
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
        boolean allowDegraded = lhProperties.getMetric() == null
                || lhProperties.getMetric().isAllowDegradedMaterialize();

        LaunchResult r = new LaunchResult();
        r.workflowCode = wfCode;
        r.processInstanceId = instanceId;
        r.degraded = degraded;
        r.sql = sqlPreview;
        r.createResp = create;
        r.startResp = start;
        r.jobPrincipal = jobPrincipal();
        r.jobRef = StrUtil.isNotBlank(instanceId) ? instanceId
                : (StrUtil.isNotBlank(wfCode) ? "wf:" + wfCode : processName);
        r.message = buildMessage(create, start, createDegraded, startDegraded, instanceId);
        r.ok = !degraded || allowDegraded;
        if (degraded) {
            log.warn("metric DS launch degraded kind={} metric={} msg={}", kind, metricCode, r.message);
        }
        return r;
    }

    private Map<String, Object> sparkTask(
            String nodeKey, String name, String sql, int timeoutMin, List<String> preTasks,
            String metricCode, String ver, String engine, String targetTable, String partitionDt) {
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
        if (StrUtil.isNotBlank(ver)) {
            params.put("ver", ver);
        }
        if (StrUtil.isNotBlank(engine)) {
            params.put("engine", engine);
        }
        if (StrUtil.isNotBlank(targetTable)) {
            params.put("target_table", targetTable);
        }
        if (StrUtil.isNotBlank(partitionDt)) {
            params.put("partition_dt", partitionDt);
        }

        Map<String, Object> task = new LinkedHashMap<>();
        task.put("nodeKey", nodeKey);
        task.put("name", name);
        task.put("engine", "spark");
        task.put("taskType", "SPARK");
        task.put("description", "metric materialize metric_code=" + metricCode);
        task.put("failRetryTimes", 1);
        task.put("failRetryInterval", 3);
        task.put("timeout", Math.max(10, timeoutMin));
        task.put("timeoutFlag", "OPEN");
        task.put("timeoutNotifyStrategy", "FAILED");
        task.put("preTasks", preTasks == null ? List.of() : preTasks);
        task.put("taskParams", params);
        task.put("x", 120);
        task.put("y", 180);
        return task;
    }

    private String jobPrincipal() {
        String p = lhProperties.getMetric() != null ? lhProperties.getMetric().getJobPrincipal() : null;
        return StrUtil.blankToDefault(p, "job.metric");
    }

    private int timeoutMinutes() {
        Integer t = lhProperties.getMetric() != null ? lhProperties.getMetric().getMaterializeTimeoutMinutes() : null;
        return t != null && t > 0 ? t : 60;
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
