package vip.xiaonuo.lh.modular.lifecycle.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.engine.DsClient;
import vip.xiaonuo.lh.core.engine.SparkSubmitBuilder;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcPolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 生命周期 Procedure → DolphinScheduler：投影 Spark SQL SHELL 工作流并启动实例。
 */
@Component
public class GovLcDsLauncher {

    private static final Logger log = LoggerFactory.getLogger(GovLcDsLauncher.class);

    @Resource
    private DsClient dsClient;
    @Resource
    private LhProperties lhProperties;

    public String sparkCatalog() {
        String c = lhProperties.getLifecycle() != null ? lhProperties.getLifecycle().getSparkCatalog() : null;
        if (StrUtil.isNotBlank(c)) {
            return c.trim();
        }
        if (lhProperties.getGravitino() != null && StrUtil.isNotBlank(lhProperties.getGravitino().getCatalog())) {
            return lhProperties.getGravitino().getCatalog().trim();
        }
        return "iceberg";
    }

    public String jobPrincipal() {
        String p = lhProperties.getLifecycle() != null ? lhProperties.getLifecycle().getJobPrincipal() : null;
        return StrUtil.blankToDefault(p, "job.lifecycle");
    }

    public LaunchResult launchTableAction(String kind, String tableFqn, GovLcPolicy policy, String runId,
                                           boolean dryRun, Integer retainLast) {
        String catalog = sparkCatalog();
        String sql;
        String nodeKey;
        switch (StrUtil.blankToDefault(kind, "").toLowerCase(Locale.ROOT)) {
            case "expire" -> {
                sql = GovLcProcedureSql.script(List.of(
                        GovLcProcedureSql.expire(catalog, tableFqn, policy, retainLast)));
                nodeKey = "expire";
            }
            case "compact", "rewrite" -> {
                sql = GovLcProcedureSql.script(List.of(GovLcProcedureSql.rewrite(catalog, tableFqn, policy)));
                nodeKey = "rewrite";
            }
            case "orphan" -> {
                sql = GovLcProcedureSql.script(List.of(
                        GovLcProcedureSql.removeOrphan(catalog, tableFqn, policy, dryRun)));
                nodeKey = "orphan";
            }
            default -> throw new IllegalArgumentException("unsupported lifecycle kind: " + kind);
        }
        String wfName = prefix() + "_" + nodeKey + "_" + GovLcProcedureSql.safeIdent(tableFqn);
        if (retainLast != null && retainLast > 0) {
            wfName = wfName + "_rl" + retainLast;
        }
        int timeout = timeoutMinutes(false);
        return submit(wfName, List.of(sparkTask(nodeKey, nodeKey + " " + tableFqn, sql, timeout, List.of())),
                List.of(), runId, kind, tableFqn, sql, timeout, null);
    }

    public LaunchResult launchProfileDaily(String ws, String runId, int tableCount) {
        String name = namedWorkflow(profileWorkflowName(), ws);
        String sql = GovLcProcedureSql.script(List.of(
                "-- " + name + " job_principal=" + jobPrincipal(),
                "-- writeback SoT is portal Trino → gov_lc_table_stat; VM is not written here",
                "SELECT " + Math.max(0, tableCount) + " AS _lh_profile_tables"));
        int timeout = 30;
        return submit(name, List.of(sparkTask("profile_daily", "存储画像日批", sql, timeout, List.of())),
                List.of(), runId, "profile", null, sql, timeout, "0 30 3 * * *");
    }

    public LaunchResult launchOrphanDryRunBatch(String ws, List<GovLcPolicy> policies, String runId) {
        String catalog = sparkCatalog();
        List<String> stmts = new ArrayList<>();
        stmts.add("-- orphan dry_run batch ws=" + ws);
        for (GovLcPolicy p : policies) {
            if (p == null || StrUtil.isBlank(p.getTableFqn())) {
                continue;
            }
            if (!"active".equalsIgnoreCase(StrUtil.blankToDefault(p.getStatus(), "active"))) {
                continue;
            }
            stmts.add(GovLcProcedureSql.removeOrphan(catalog, p.getTableFqn(), p, true));
        }
        if (stmts.size() <= 1) {
            stmts.add("SELECT 1 AS _lh_lc_orphan_empty");
        }
        String sql = GovLcProcedureSql.script(stmts);
        String wfName = prefix() + "_orphan_dry_" + GovLcProcedureSql.safeIdent(StrUtil.blankToDefault(ws, "default"));
        int timeout = timeoutMinutes(false);
        return submit(wfName, List.of(sparkTask("orphan_dry_run", "孤儿 dry-run", sql, timeout, List.of())),
                List.of(), runId, "orphan", null, sql, timeout, null);
    }

    public LaunchResult launchDaily(String ws, List<GovLcPolicy> policies, String runId, String batchId) {
        String catalog = sparkCatalog();
        boolean skipL1 = lhProperties.getLifecycle() == null
                || lhProperties.getLifecycle().isSkipL1RewriteInDaily();
        List<String> expireSql = new ArrayList<>();
        List<String> rewriteSql = new ArrayList<>();
        List<String> orphanSql = new ArrayList<>();
        List<String> partSql = new ArrayList<>();
        for (GovLcPolicy p : policies) {
            if (p == null || StrUtil.isBlank(p.getTableFqn())) {
                continue;
            }
            if (!"active".equalsIgnoreCase(StrUtil.blankToDefault(p.getStatus(), "active"))) {
                continue;
            }
            String fqn = p.getTableFqn();
            expireSql.add(GovLcProcedureSql.expire(catalog, fqn, p));
            String level = StrUtil.blankToDefault(p.getCompactLevel(), "L2").toUpperCase(Locale.ROOT);
            if (!(skipL1 && "L1".equals(level))) {
                rewriteSql.add(GovLcProcedureSql.rewrite(catalog, fqn, p));
            } else {
                rewriteSql.add("-- skip L1 rewrite (Flink auto-compaction): " + fqn);
            }
            orphanSql.add(GovLcProcedureSql.removeOrphan(catalog, fqn, p, true));
            partSql.add(GovLcProcedureSql.partitionExpirePlaceholder(fqn, p));
        }
        if (expireSql.isEmpty()) {
            expireSql.add("SELECT 1 AS _lh_lc_no_policy");
        }
        // 日作业同批不做 orphan 物理删（须 expire +72h）；本步 dry-run 扫描
        orphanSql.add(0, "-- daily orphan step is dry_run only; physical delete after safety window");
        int timeout = timeoutMinutes(true);
        List<Map<String, Object>> tasks = new ArrayList<>();
        tasks.add(sparkTask("expire_snapshots", "快照过期", GovLcProcedureSql.script(expireSql), timeout, List.of()));
        tasks.add(sparkTask("rewrite_data_files", "小文件合并", GovLcProcedureSql.script(rewriteSql), timeout,
                List.of("expire_snapshots")));
        tasks.add(sparkTask("remove_orphan_files", "孤儿清理", GovLcProcedureSql.script(orphanSql), timeout,
                List.of("rewrite_data_files")));
        tasks.add(sparkTask("expire_partitions", "分区过期", GovLcProcedureSql.script(partSql), Math.min(timeout, 30),
                List.of("remove_orphan_files")));

        List<Map<String, Object>> relations = List.of(
                Map.of("from", "expire_snapshots", "to", "rewrite_data_files"),
                Map.of("from", "rewrite_data_files", "to", "remove_orphan_files"),
                Map.of("from", "remove_orphan_files", "to", "expire_partitions")
        );
        String wfName = namedWorkflow(dailyWorkflowName(), ws);
        String sqlPreview = "daily batch=" + batchId + " tables=" + policies.size()
                + " template=" + wfName + " job_principal=" + jobPrincipal();
        return submit(wfName, tasks, relations, runId, "daily", null, sqlPreview, timeout, "0 2 * * *");
    }

    public Map<String, Object> syncInstance(String processInstanceId) {
        if (StrUtil.isBlank(processInstanceId) || processInstanceId.startsWith("ds-stub-")) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("message", "无有效 DS processInstanceId");
            return out;
        }
        Map<String, Object> inst = dsClient.getProcessInstance(processInstanceId);
        String state = StrUtil.blankToDefault(String.valueOf(inst.get("state")), "");
        inst.put("mappedStatus", mapDsState(state));
        return inst;
    }

    public static String mapDsState(String state) {
        String s = StrUtil.blankToDefault(state, "").toUpperCase(Locale.ROOT);
        if (s.contains("SUCCESS")) {
            return "success";
        }
        if (s.contains("FAIL") || s.contains("KILL") || s.contains("STOP")) {
            return "failed";
        }
        if (s.contains("RUNNING") || s.contains("SUBMIT") || s.contains("READY") || s.contains("DELAY")
                || s.contains("WAIT") || s.contains("QUEUE") || s.contains("DISPATCH")) {
            return "running";
        }
        if (StrUtil.isBlank(s) || "NULL".equals(s)) {
            return "queued";
        }
        return "running";
    }

    private LaunchResult submit(
            String processName,
            List<Map<String, Object>> tasks,
            List<Map<String, Object>> relations,
            String runId,
            String kind,
            String tableFqn,
            String sqlPreview,
            int timeoutMinutes,
            String cron) {

        Map<String, Object> workflow = new LinkedHashMap<>();
        workflow.put("workflowCode", processName);
        workflow.put("name", processName);
        workflow.put("description", "lakehouse lifecycle " + kind + " run=" + runId
                + " job_principal=" + jobPrincipal());
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
        startParams.put("job_principal", jobPrincipal());
        if (StrUtil.isNotBlank(tableFqn)) {
            startParams.put("table_fqn", tableFqn);
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
        boolean allowDegraded = lhProperties.getLifecycle() == null
                || lhProperties.getLifecycle().isAllowDegraded();

        LaunchResult r = new LaunchResult();
        r.workflowCode = wfCode;
        r.processInstanceId = instanceId;
        r.degraded = degraded;
        r.sql = sqlPreview;
        r.createResp = create;
        r.startResp = start;
        r.message = buildMessage(create, start, createDegraded, startDegraded, instanceId);
        r.jobPrincipal = jobPrincipal();
        r.dsTaskId = StrUtil.isNotBlank(instanceId) ? instanceId
                : (StrUtil.isNotBlank(wfCode) ? "wf:" + wfCode : null);
        r.ok = !degraded || allowDegraded;
        if (degraded) {
            log.warn("lifecycle DS launch degraded kind={} table={} msg={}", kind, tableFqn, r.message);
        }
        return r;
    }

    private Map<String, Object> sparkTask(
            String nodeKey, String name, String sql, int timeoutMin, List<String> preTasks) {
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

        Map<String, Object> task = new LinkedHashMap<>();
        task.put("nodeKey", nodeKey);
        task.put("name", name);
        task.put("engine", "spark");
        task.put("taskType", "SPARK");
        task.put("description", "Iceberg procedure via spark-sql");
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

    private String prefix() {
        String p = lhProperties.getLifecycle() != null ? lhProperties.getLifecycle().getWorkflowPrefix() : null;
        return StrUtil.blankToDefault(p, "lh_lc");
    }

    private String dailyWorkflowName() {
        String n = lhProperties.getLifecycle() != null ? lhProperties.getLifecycle().getDailyWorkflowName() : null;
        return StrUtil.blankToDefault(n, "job.iceberg.lifecycle");
    }

    private String profileWorkflowName() {
        String n = lhProperties.getLifecycle() != null ? lhProperties.getLifecycle().getProfileWorkflowName() : null;
        return StrUtil.blankToDefault(n, "job.storage.profile_daily");
    }

    private static String namedWorkflow(String base, String ws) {
        if (StrUtil.isBlank(ws) || "default".equalsIgnoreCase(ws.trim())) {
            return base;
        }
        return base + "_" + GovLcProcedureSql.safeIdent(ws);
    }

    private int timeoutMinutes(boolean daily) {
        LhProperties.Lifecycle lc = lhProperties.getLifecycle();
        if (lc == null) {
            return daily ? 180 : 60;
        }
        return daily ? Math.max(30, lc.getDailyTimeoutMinutes()) : Math.max(10, lc.getTimeoutMinutes());
    }

    private static String buildMessage(
            Map<String, Object> create,
            Map<String, Object> start,
            boolean createDegraded,
            boolean startDegraded,
            String instanceId) {
        if (!createDegraded && !startDegraded && StrUtil.isNotBlank(instanceId)) {
            return "已提交 DS 实例 " + instanceId;
        }
        StringBuilder sb = new StringBuilder();
        if (createDegraded) {
            sb.append("投影降级: ").append(str(create.get("message"), str(create.get("resp"), "degraded")));
        }
        if (startDegraded || StrUtil.isBlank(instanceId)) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append("启动: ").append(str(start.get("message"),
                    StrUtil.isBlank(instanceId) ? "无 processInstanceId" : str(start.get("resp"), "degraded")));
        }
        return sb.toString();
    }

    private static String str(Object o, String dft) {
        if (o == null) {
            return dft;
        }
        String s = String.valueOf(o);
        return StrUtil.isBlank(s) || "null".equalsIgnoreCase(s) ? dft : s;
    }

    public static final class LaunchResult {
        public boolean ok;
        public boolean degraded;
        public String workflowCode;
        public String processInstanceId;
        public String dsTaskId;
        public String message;
        public String sql;
        public String jobPrincipal;
        public Map<String, Object> createResp;
        public Map<String, Object> startResp;

        public Map<String, Object> toMetricsJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("jobPrincipal", jobPrincipal);
            m.put("stub", false);
            m.put("degraded", degraded);
            m.put("workflowCode", workflowCode);
            m.put("processInstanceId", processInstanceId);
            m.put("message", message);
            if (StrUtil.isNotBlank(sql) && sql.length() < 4000) {
                m.put("sqlPreview", sql);
            }
            if (createResp != null) {
                m.put("createDegraded", createResp.get("degraded"));
            }
            if (startResp != null) {
                m.put("startPendingVerify", startResp.get("pendingVerify"));
            }
            return m;
        }

        @Override
        public String toString() {
            return JSONUtil.toJsonStr(toMetricsJson());
        }
    }
}
