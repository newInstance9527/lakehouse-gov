/*
 * Copyright [2022] [https://www.xiaonuo.vip]
 *
 * Snowy采用APACHE LICENSE 2.0开源协议，您在使用过程中，需要注意以下几点：
 *
 * 1.请不要删除和修改根目录下的LICENSE文件。
 * 2.请不要删除和修改Snowy源码头部的版权声明。
 * 3.本项目代码可免费商业使用，商业使用请保留源码和相关描述文件的项目出处，作者声明等。
 * 4.分发源码时候，请注明软件出处 https://www.xiaonuo.vip
 * 5.不可二次分发开源参与同类竞品，如有想法可联系团队xiaonuobase@qq.com商议合作。
 * 6.若您的项目无法满足以上几点，需要更多功能代码，获取Snowy商业授权许可，请在官网购买授权，地址为 https://www.xiaonuo.vip
 */
package vip.xiaonuo.lh.core.engine;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.vault.LhComponentCredentialResolver;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DolphinScheduler 客户端（凭证：user/password → POST /login → sessionId）
 * <p>本环境请求只带 {@code sessionId} 头；带 {@code token} 头会 401。创建/更新 Workflow soft-fail。</p>
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Component
public class DsClient {

    private static final Logger log = LoggerFactory.getLogger(DsClient.class);
    /** session 缓存约 25 分钟（DS 默认会话更长，提前刷新） */
    private static final long SESSION_TTL_MS = 25L * 60L * 1000L;

    @Resource
    private LhProperties lhProperties;
    @Resource
    private LhComponentCredentialResolver credentialResolver;

    private volatile String cachedSessionToken;
    private volatile long cachedSessionAtMs;
    private volatile String cachedTenantCode;
    private volatile long cachedTenantAtMs;

    /**
     * 创建或更新工作流（基于 {@link DsWorkflowBuilder} 投影）
     * <p>DS 3.2 官方创建接口为 form：taskDefinitionJson / taskRelationJson / locations，
     * 不可把门户投影 JSON 直接 POST（会报 create process definition error）。</p>
     */
    public Map<String, Object> createOrUpdateWorkflow(Map<String, Object> workflow) {
        String workflowCode = str(workflow.get("workflowCode"), "WF_unknown");
        String name = str(workflow.get("name"), workflowCode);
        String projectCode = StrUtil.blankToDefault(lhProperties.getDs().getProjectCode(), "1");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engine", "dolphinscheduler");
        out.put("workflowCode", workflowCode);
        out.put("projectCode", projectCode);
        out.put("name", name);
        out.put("cron", str(workflow.get("cron"), "0 2 * * *"));
        out.put("taskCount", workflow.get("taskCount"));
        out.put("edgeCount", workflow.get("edgeCount"));

        String base = trim(lhProperties.getDs().getUrl());
        if (StrUtil.isBlank(base)) {
            out.put("ok", true);
            out.put("degraded", true);
            out.put("message", "lh.ds.url 未配置，已仅登记 workflow 投影");
            out.put("projection", workflow);
            return out;
        }

        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> portalTasks = (List<Map<String, Object>>) workflow.get("tasks");
            if (portalTasks == null || portalTasks.isEmpty()) {
                out.put("ok", true);
                out.put("degraded", true);
                out.put("message", "无任务节点，跳过 DS 创建");
                return out;
            }

            List<Long> codes = genTaskCodes(base, projectCode, portalTasks.size());
            Map<String, Long> codeByNode = new LinkedHashMap<>();
            List<Map<String, Object>> taskDefs = new java.util.ArrayList<>();
            List<Map<String, Object>> locations = new java.util.ArrayList<>();
            for (int i = 0; i < portalTasks.size(); i++) {
                Map<String, Object> t = portalTasks.get(i);
                String nodeKey = str(t.get("nodeKey"), "n" + i);
                long taskCode = codes.get(i);
                codeByNode.put(nodeKey, taskCode);
                // 按引擎投 DS 原生任务类型（FLINK/SPARK/DATAX/SQL/SHELL），禁止再一律压成 SHELL
                String engine = str(t.get("engine"), "");
                String rawType = str(t.get("taskType"), "SHELL");
                @SuppressWarnings("unchecked")
                Map<String, Object> rawParams = t.get("taskParams") instanceof Map
                        ? new LinkedHashMap<>((Map<String, Object>) t.get("taskParams"))
                        : new LinkedHashMap<>();
                String dsType = resolveDsTaskType(rawType, engine);
                // Spark SQL：local / spark:// 用 SHELL（避免 DS 假容错重派）；yarn 用原生 SPARK（application_ 可跟踪）
                if ("SPARK".equals(dsType) && !hasSparkJar(rawParams)) {
                    String sparkSql = firstNonBlank(
                            str(rawParams.get("rawScript"), null),
                            str(rawParams.get("lhSql"), null),
                            str(rawParams.get("sql"), null),
                            "SELECT 1");
                    String master = firstNonBlank(
                            str(rawParams.get("master"), null),
                            str(rawParams.get("sparkMaster"), null),
                            "local[*]");
                    if (isYarnSparkMaster(master)) {
                        rawParams.put("rawScript", sparkSql);
                        rawParams.put("programType", "SQL");
                        rawParams.put("sqlExecutionType", "SCRIPT");
                        rawParams.put("deployMode", firstNonBlank(str(rawParams.get("deployMode"), null), "client"));
                        rawParams.put("master", normalizeYarnMaster(master));
                        // 保持 dsType=SPARK，走 YARN ApplicationManager
                    } else {
                        rawParams.put("rawScript", SparkSubmitBuilder.buildSqlShell(nodeKey, sparkSql, master));
                        rawParams.put("lhSparkSql", sparkSql);
                        rawParams.put("lhPreferShell", true);
                        dsType = "SHELL";
                    }
                }
                // DataX：DS 3.2 原生任务依赖登录态 PYTHON_LAUNCHER/DATAX_LAUNCHER，容器易丢；统一 SHELL
                if ("DATAX".equals(dsType)) {
                    Object jobObj = rawParams.get("lhDataxJob");
                    String jobJson;
                    if (jobObj instanceof Map || jobObj instanceof List) {
                        jobJson = cn.hutool.json.JSONUtil.toJsonStr(jobObj);
                    } else {
                        jobJson = firstNonBlank(str(rawParams.get("json"), null), str(jobObj, null), null);
                    }
                    IgEtlNode tmp = new IgEtlNode();
                    tmp.setNodeKey(nodeKey);
                    cn.hutool.json.JSONObject conf = new cn.hutool.json.JSONObject();
                    if (StrUtil.isNotBlank(jobJson)) {
                        // 已有完整 job 时走壳脚本直跑 json；否则由 DataxJobBuilder 从 conf 生成
                        rawParams.put("rawScript", DataxJobBuilder.buildShellFromJobJson(nodeKey, jobJson));
                    } else {
                        rawParams.put("rawScript", DataxJobBuilder.buildShell(tmp, conf));
                    }
                    rawParams.put("lhPreferShell", true);
                    dsType = "SHELL";
                }
                Map<String, Object> taskParams = toDsTaskParams(rawParams, dsType, nodeKey, engine);

                Map<String, Object> def = new LinkedHashMap<>();
                def.put("code", taskCode);
                def.put("delayTime", 0);
                def.put("description", truncate(str(t.get("description"), nodeKey), 200));
                def.put("environmentCode", -1);
                // 默认重试 3 次、间隔 3 分钟；可由任务投影覆盖（次数 ≤3）
                int retryInterval = 3;
                Object fri = t.get("failRetryInterval");
                if (fri instanceof Number n) {
                    retryInterval = Math.max(1, Math.min(n.intValue(), 60));
                } else if (fri != null) {
                    try {
                        retryInterval = Math.max(1, Math.min(Integer.parseInt(String.valueOf(fri).trim()), 60));
                    } catch (NumberFormatException ignored) {
                        retryInterval = 3;
                    }
                }
                def.put("failRetryInterval", retryInterval);
                int retries = 3;
                Object fr = t.get("failRetryTimes");
                if (fr instanceof Number n) {
                    retries = Math.max(0, Math.min(n.intValue(), 3));
                } else if (fr != null) {
                    try {
                        retries = Math.max(0, Math.min(Integer.parseInt(String.valueOf(fr).trim()), 3));
                    } catch (NumberFormatException ignored) {
                        retries = 3;
                    }
                }
                def.put("failRetryTimes", retries);
                def.put("flag", "YES");
                def.put("isCache", "NO");
                def.put("name", sanitizeTaskName(str(t.get("name"), nodeKey)));
                def.put("taskParams", taskParams);
                def.put("taskPriority", "MEDIUM");
                def.put("taskType", dsType);
                // DS 3.2：缺省 taskExecuteType=null 时 Master taskFinished 打日志 NPE（logTaskInstanceInDetail），
                // 会把任务移出 completeTaskSet 并无限重派下游（社区 #15055）。
                def.put("taskExecuteType", "BATCH");
                // Flink/Spark/DataX（含 Spark-SQL→SHELL）：开启超时（分钟），避免失败后长时间空转/重试
                boolean sparkSqlShell = "SHELL".equals(dsType) && Boolean.TRUE.equals(rawParams.get("lhPreferShell"));
                if ("FLINK".equals(dsType) || "FLINK_STREAM".equals(dsType)
                        || "SPARK".equals(dsType) || "DATAX".equals(dsType) || sparkSqlShell) {
                    int timeoutMin = 30;
                    Object to = t.get("timeout");
                    if (to instanceof Number n && n.intValue() > 0) {
                        timeoutMin = Math.min(n.intValue(), 180);
                    }
                    def.put("timeout", timeoutMin);
                    def.put("timeoutFlag", "OPEN");
                    def.put("timeoutNotifyStrategy", "FAILED");
                } else {
                    def.put("timeout", 0);
                    def.put("timeoutFlag", "CLOSE");
                    def.put("timeoutNotifyStrategy", "");
                }
                def.put("workerGroup", "default");
                def.put("cpuQuota", -1);
                def.put("memoryMax", -1);
                def.put("taskGroupId", 0);
                def.put("taskGroupPriority", 0);
                taskDefs.add(def);

                Map<String, Object> loc = new LinkedHashMap<>();
                loc.put("taskCode", taskCode);
                loc.put("x", t.get("x") != null ? t.get("x") : (100 + i * 180));
                loc.put("y", t.get("y") != null ? t.get("y") : 200);
                // 门户 locations 可能在 workflow.locations
                locations.add(loc);
            }
            // 覆盖坐标（若 builder 已给）
            Object locs = workflow.get("locations");
            if (locs instanceof List<?> locList) {
                for (Object o : locList) {
                    if (!(o instanceof Map<?, ?> m)) {
                        continue;
                    }
                    String nk = str(m.get("nodeKey"), null);
                    Long code = nk == null ? null : codeByNode.get(nk);
                    if (code == null && m.get("taskCode") instanceof Number num) {
                        code = num.longValue();
                    }
                    if (code == null) {
                        continue;
                    }
                    for (Map<String, Object> loc : locations) {
                        if (code.equals(toLong(loc.get("taskCode")))) {
                            if (m.get("x") != null) {
                                loc.put("x", m.get("x"));
                            }
                            if (m.get("y") != null) {
                                loc.put("y", m.get("y"));
                            }
                        }
                    }
                }
            }

            List<Map<String, Object>> relations = buildDsRelations(portalTasks, codeByNode, workflow.get("taskRelation"));

            String processName = sanitizeProcessName(name);
            if (Boolean.TRUE.equals(workflow.get("trial"))) {
                processName = sanitizeProcessName(name + "_trial");
            }
            Map<String, Object> form = new LinkedHashMap<>();
            form.put("name", processName);
            form.put("description", truncate(str(workflow.get("description"), workflowCode), 255));
            form.put("globalParams", "[]");
            form.put("locations", JSONUtil.toJsonStr(locations));
            form.put("timeout", "0");
            form.put("taskRelationJson", JSONUtil.toJsonStr(relations));
            form.put("taskDefinitionJson", JSONUtil.toJsonStr(taskDefs));
            form.put("otherParamsJson", "");
            form.put("executionType", str(workflow.get("executionType"), "PARALLEL"));
            // 必须与 DS 登录用户绑定租户一致（本环境 admin → root）；admin 不可用 default
            form.put("tenantCode", resolveTenantCode(base));

            // 同名已存在则更新；否则创建。ONLINE 流程禁止编辑 → 先 OFFLINE 再 PUT
            Long existingCode = findProcessCodeByName(base, projectCode, processName);
            String body;
            if (existingCode != null) {
                tryReleaseState(base, projectCode, String.valueOf(existingCode), "OFFLINE");
                String url = base + "/projects/" + projectCode + "/process-definition/" + existingCode;
                body = executeWithAuth(() -> HttpRequest.put(url)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .form(form)
                        .timeout(30000));
                out.put("workflowCode", String.valueOf(existingCode));
                out.put("updated", true);
            } else {
                String url = base + "/projects/" + projectCode + "/process-definition";
                body = executeWithAuth(() -> HttpRequest.post(url)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .form(form)
                        .timeout(30000));
            }

            out.put("ok", true);
            out.put("degraded", false);
            out.put("resp", truncate(body, 2000));
            JSONObject jo = JSONUtil.parseObj(body);
            if (jo.containsKey("code") && jo.getInt("code", -1) != 0) {
                out.put("degraded", true);
                out.put("message", jo.getStr("msg", "create process definition error"));
                return out;
            }
            Object data = jo.get("data");
            String remoteCode = extractProcessDefinitionCode(data);
            if (StrUtil.isNotBlank(remoteCode)) {
                out.put("workflowCode", remoteCode);
            } else if (existingCode != null) {
                out.put("workflowCode", String.valueOf(existingCode));
            }
            // 试跑/启动要求流程 ONLINE
            tryReleaseState(base, projectCode, String.valueOf(out.get("workflowCode")), "ONLINE");
            return out;
        } catch (Exception e) {
            log.warn("DS createOrUpdateWorkflow soft-fail workflowCode={}: {}", workflowCode, e.getMessage());
            out.put("ok", true);
            out.put("degraded", true);
            out.put("message", e.getMessage());
            out.put("projection", workflow);
            return out;
        }
    }

    /**
     * 本环境 DS 3.2 已安装 FLINK / SPARK / DATAX / SQL / SHELL 等插件。
     * Flink/Spark 无 jar 时用 programType=SQL + rawScript；DataX 用 customConfig=1 + json。
     */
    private static boolean hasSparkJar(Map<String, Object> rawParams) {
        if (rawParams == null) {
            return false;
        }
        Object jarRes = rawParams.get("mainJar");
        if (jarRes instanceof Map<?, ?> m && m.get("id") != null) {
            return true;
        }
        return StrUtil.isNotBlank(str(rawParams.get("jar"), null))
                || StrUtil.isNotBlank(str(rawParams.get("sparkJar"), null))
                || StrUtil.isNotBlank(str(rawParams.get("appJar"), null));
    }

    /** yarn / yarn-cluster / yarn-client → 走 DS 原生 SPARK + YARN 跟踪 */
    private static boolean isYarnSparkMaster(String master) {
        String m = StrUtil.blankToDefault(master, "").trim().toLowerCase(java.util.Locale.ROOT);
        return m.equals("yarn") || m.startsWith("yarn-") || m.contains("yarn");
    }

    private static String normalizeYarnMaster(String master) {
        String m = StrUtil.blankToDefault(master, "yarn").trim();
        if (m.equalsIgnoreCase("yarn-client") || m.equalsIgnoreCase("yarn-cluster")) {
            return "yarn";
        }
        return m;
    }

    private static String resolveDsTaskType(String rawType, String engine) {
        String t = StrUtil.blankToDefault(rawType, "").toUpperCase();
        if ("SWITCH".equals(t) || "DEPENDENT".equals(t) || "CONDITIONS".equals(t)
                || "HTTP".equals(t) || "SHELL".equals(t) || "SQL".equals(t)
                || "FLINK".equals(t) || "SPARK".equals(t) || "DATAX".equals(t)
                || "FLINK_STREAM".equals(t)) {
            // SQL 依赖 DS 内置数据源；本环境 datasources 为空 → 改 SHELL+Trino
            if ("SQL".equals(t)) {
                return "SHELL";
            }
            return t;
        }
        if ("flink".equalsIgnoreCase(engine)) {
            return "FLINK";
        }
        if ("spark".equalsIgnoreCase(engine)) {
            return "SPARK";
        }
        if ("datax".equalsIgnoreCase(engine)) {
            return "DATAX";
        }
        if ("ds_sql".equalsIgnoreCase(engine)) {
            return "SHELL";
        }
        return "SHELL";
    }

    /** 按 DS 原生任务类型组装 taskParams（对齐 3.2 插件字段） */
    private static Map<String, Object> toDsTaskParams(
            Map<String, Object> raw, String dsType, String nodeKey, String engine) {
        Map<String, Object> p = baseTaskParams();
        // 保留 Vault 注入的 localParams / env，供 ${LH_JDBC_*} 运行时替换
        copyRuntimeSecrets(raw, p);
        String sql = firstNonBlank(str(raw.get("lhSql"), null), str(raw.get("sql"), null), "SELECT 1");
        String script = firstNonBlank(str(raw.get("rawScript"), null), null);

        if ("FLINK".equals(dsType) || "FLINK_STREAM".equals(dsType)) {
            // programType=SQL 时 rawScript 为带占位符的 Flink SQL（凭证在 localParams）
            Object jarRes = raw.get("mainJar");
            boolean hasJar = jarRes instanceof Map && ((Map<?, ?>) jarRes).get("id") != null;
            if (hasJar) {
                p.put("programType", firstNonBlank(str(raw.get("programType"), null), "JAVA"));
                p.put("mainJar", jarRes);
                p.put("mainClass", firstNonBlank(str(raw.get("mainClass"), null), str(raw.get("entryClass"), null), ""));
                p.put("mainArgs", StrUtil.blankToDefault(str(raw.get("mainArgs"), null), ""));
                p.put("rawScript", "");
            } else {
                // DS Flink SQL 插件不会把 localParams 展开进 rawScript；${LH_JDBC_*} 会原样进集群导致 JDBC 鉴权失败。
                // 发布时用 localParams 展开（凭证本就在 localParams / env 中）。
                String flinkSql = pickFlinkSql(raw, sql, script);
                flinkSql = flinkSql.endsWith(";") ? flinkSql : flinkSql + ";";
                p.put("programType", "SQL");
                p.put("rawScript", expandLocalParamPlaceholders(flinkSql, p));
                p.put("initScript", firstNonBlank(str(raw.get("initScript"), null), defaultFlinkRemoteInitScript()));
                p.put("mainClass", "");
                p.put("mainArgs", "");
            }
            // DS 3.2：local=Worker 内嵌集群（Flink UI 看不到）；standalone=提交到本环境 lh-flink-jm
            p.put("deployMode", firstNonBlank(str(raw.get("deployMode"), null), "standalone"));
            p.put("flinkVersion", firstNonBlank(str(raw.get("flinkVersion"), null), ">=1.13"));
            p.put("parallelism", raw.get("parallelism") instanceof Number n ? n.intValue() : 1);
            p.put("slot", raw.get("slot") instanceof Number n ? n.intValue() : 1);
            p.put("taskManager", raw.get("taskManager") instanceof Number n ? n.intValue() : 2);
            p.put("jobManagerMemory", firstNonBlank(str(raw.get("jobManagerMemory"), null), "1G"));
            p.put("taskManagerMemory", firstNonBlank(str(raw.get("taskManagerMemory"), null), "2G"));
            p.put("appName", "lh_" + sanitizeTaskName(nodeKey));
            p.put("others", StrUtil.blankToDefault(str(raw.get("others"), null), ""));
            return p;
        }
        if ("SPARK".equals(dsType)) {
            Object jarRes = raw.get("mainJar");
            boolean hasJar = jarRes instanceof Map && ((Map<?, ?>) jarRes).get("id") != null;
            if (hasJar) {
                p.put("programType", firstNonBlank(str(raw.get("programType"), null), "JAVA"));
                p.put("mainJar", jarRes);
                p.put("mainClass", firstNonBlank(str(raw.get("mainClass"), null), ""));
                p.put("mainArgs", StrUtil.blankToDefault(str(raw.get("mainArgs"), null), ""));
                p.put("rawScript", "");
            } else {
                // 优先已含占位符的 rawScript；否则用 lhSql
                String sparkSql = firstNonBlank(script, sql);
                p.put("programType", "SQL");
                p.put("rawScript", sparkSql);
                p.put("sqlExecutionType", "SCRIPT");
                p.put("mainClass", "");
                p.put("mainArgs", "");
            }
            p.put("deployMode", firstNonBlank(str(raw.get("deployMode"), null), "local"));
            p.put("master", firstNonBlank(str(raw.get("master"), null), "local[*]"));
            p.put("driverCores", 1);
            p.put("driverMemory", "512M");
            p.put("numExecutors", 1);
            p.put("executorMemory", "1G");
            p.put("executorCores", 1);
            p.put("appName", "lh_" + sanitizeTaskName(nodeKey));
            p.put("yarnQueue", StrUtil.blankToDefault(str(raw.get("yarnQueue"), null), ""));
            // deployMode=local：DS SparkTask 走本地路径，不再 --deploy-mode client，避免 Master 按 YARN app 容错重派
            p.put("others", StrUtil.blankToDefault(str(raw.get("others"), null),
                    "--jars /opt/spark/jars/mysql-connector-j-8.0.33.jar"));
            return p;
        }
        if ("DATAX".equals(dsType)) {
            p.put("customConfig", 1);
            Object job = raw.get("lhDataxJob");
            String json;
            if (job instanceof Map || job instanceof List) {
                json = JSONUtil.toJsonStr(job);
            } else {
                json = firstNonBlank(str(raw.get("json"), null), str(job, null), null);
            }
            if (StrUtil.isBlank(json)) {
                json = "{\"job\":{\"setting\":{\"speed\":{\"channel\":1}},\"content\":[]}}";
            }
            p.put("json", json);
            p.put("xms", 1);
            p.put("xmx", 1);
            return p;
        }
        // SHELL / 其它：可执行脚本（含 Trino/Flink REST 兜底）
        p.put("rawScript", firstNonBlank(script,
                "echo '[lh] node=" + nodeKey + " engine=" + engine + " type=" + dsType + "'"));
        return p;
    }

    private static Map<String, Object> baseTaskParams() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("localParams", List.of());
        p.put("resourceList", List.of());
        p.put("dependence", Map.of());
        p.put("conditionResult", Map.of("successNode", List.of(), "failedNode", List.of()));
        p.put("waitStartTimeout", Map.of());
        p.put("switchResult", Map.of());
        return p;
    }

    /** 把 VaultInjector 写入的 localParams / env 带进 DS 任务定义 */
    @SuppressWarnings("unchecked")
    private static void copyRuntimeSecrets(Map<String, Object> raw, Map<String, Object> p) {
        if (raw == null || p == null) {
            return;
        }
        Object lp = raw.get("localParams");
        if (lp instanceof List<?> list && !list.isEmpty()) {
            LinkedHashMap<String, Map<String, Object>> byProp = new LinkedHashMap<>();
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                Object propObj = m.get("prop");
                if (propObj == null || StrUtil.isBlank(String.valueOf(propObj))) {
                    continue;
                }
                String prop = String.valueOf(propObj).trim();
                Object val = m.get("value");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("prop", prop);
                // DS Direct 枚举仅 IN/OUT；TRUE/FALSE/null 会 NPE
                row.put("direct", "IN");
                row.put("type", "VARCHAR");
                row.put("value", val == null ? "" : String.valueOf(val));
                byProp.put(prop, row);
            }
            if (!byProp.isEmpty()) {
                p.put("localParams", new java.util.ArrayList<>(byProp.values()));
            }
        }
        Object env = raw.get("env");
        if (env instanceof Map<?, ?> em && !em.isEmpty()) {
            p.put("env", new LinkedHashMap<>((Map<String, Object>) em));
        }
    }

    /**
     * DS Flink SQL standalone：提交到 Docker 内 lh-flink-jm，便于 Flink Web UI 可见。
     * {@code execution.attached=true}：sql-client 等 BATCH 写完再退出，避免 DS 仅因「已提交」判 SUCCESS。
     */
    private static String defaultFlinkRemoteInitScript() {
        return "SET 'execution.target' = 'remote';\n"
                + "SET 'rest.address' = 'flink-jobmanager';\n"
                + "SET 'rest.port' = '8081';\n"
                + "SET 'execution.attached' = 'true';\n"
                + "SET 'execution.shutdown-on-attached-exit' = 'true';\n";
    }

    /** 将 localParams 中的 ${PROP} 展开进脚本（Flink SQL WITH 字面量需转义单引号）。 */
    @SuppressWarnings("unchecked")
    private static String expandLocalParamPlaceholders(String script, Map<String, Object> taskParams) {
        if (StrUtil.isBlank(script) || taskParams == null) {
            return script;
        }
        Object lp = taskParams.get("localParams");
        if (!(lp instanceof List<?> list) || list.isEmpty()) {
            return script;
        }
        java.util.ArrayList<java.util.Map.Entry<String, String>> entries = new java.util.ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            Object propObj = m.get("prop");
            Object valObj = m.get("value");
            if (propObj == null || valObj == null) {
                continue;
            }
            String prop = String.valueOf(propObj).trim();
            if (prop.isEmpty()) {
                continue;
            }
            entries.add(java.util.Map.entry(prop, String.valueOf(valObj)));
        }
        entries.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));
        String out = script;
        for (java.util.Map.Entry<String, String> e : entries) {
            String escaped = e.getValue().replace("'", "''");
            out = out.replace("${" + e.getKey() + "}", escaped);
        }
        return out;
    }

    private List<Long> genTaskCodes(String base, String projectCode, int n) {
        String url = base + "/projects/" + projectCode + "/task-definition/gen-task-codes?genNum=" + Math.max(n, 1);
        String body = executeWithAuth(() -> HttpRequest.get(url).timeout(10000));
        JSONObject jo = JSONUtil.parseObj(body);
        if (jo.getInt("code", -1) != 0) {
            throw new IllegalStateException("DS gen-task-codes 失败: " + jo.getStr("msg", body));
        }
        Object data = jo.get("data");
        List<Long> codes = new java.util.ArrayList<>();
        if (data instanceof cn.hutool.json.JSONArray arr) {
            for (Object o : arr) {
                codes.add(toLong(o));
            }
        } else if (data instanceof List<?> list) {
            for (Object o : list) {
                codes.add(toLong(o));
            }
        }
        if (codes.size() < n) {
            throw new IllegalStateException("DS gen-task-codes 数量不足 need=" + n + " got=" + codes.size());
        }
        return codes.subList(0, n);
    }

    private Long findProcessCodeByName(String base, String projectCode, String name) {
        try {
            String url = base + "/projects/" + projectCode + "/process-definition?pageNo=1&pageSize=50&searchVal="
                    + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8);
            String body = executeWithAuth(() -> HttpRequest.get(url).timeout(10000));
            JSONObject jo = JSONUtil.parseObj(body);
            Object data = jo.get("data");
            if (!(data instanceof JSONObject dataObj)) {
                return null;
            }
            Object list = dataObj.get("totalList");
            if (!(list instanceof cn.hutool.json.JSONArray arr)) {
                return null;
            }
            for (Object o : arr) {
                if (o instanceof JSONObject row && name.equals(row.getStr("name"))) {
                    return toLong(row.get("code"));
                }
            }
        } catch (Exception e) {
            log.debug("findProcessCodeByName soft-fail: {}", e.getMessage());
        }
        return null;
    }

    private void tryReleaseState(String base, String projectCode, String code, String releaseState) {
        if (StrUtil.isBlank(code) || !code.chars().allMatch(Character::isDigit)) {
            return;
        }
        String state = StrUtil.blankToDefault(releaseState, "ONLINE").toUpperCase();
        try {
            String url = base + "/projects/" + projectCode + "/process-definition/" + code + "/release";
            String body = executeWithAuth(() -> HttpRequest.post(url)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .form("releaseState", state)
                    .timeout(15000));
            JSONObject jo = JSONUtil.parseObj(body);
            if (jo.containsKey("code") && jo.getInt("code", -1) != 0) {
                log.warn("DS release {} soft-fail code={}: {}", state, code, jo.getStr("msg", body));
            }
        } catch (Exception e) {
            log.warn("DS release {} soft-fail code={}: {}", state, code, e.getMessage());
        }
    }

    private static List<Map<String, Object>> buildDsRelations(
            List<Map<String, Object>> portalTasks,
            Map<String, Long> codeByNode,
            Object taskRelation) {
        List<Map<String, Object>> relations = new java.util.ArrayList<>();
        java.util.Set<String> linked = new java.util.HashSet<>();
        if (taskRelation instanceof List<?> rels) {
            for (Object o : rels) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                String from = str(m.get("from"), null);
                String to = str(m.get("to"), null);
                Long pre = from == null ? null : codeByNode.get(from);
                Long post = to == null ? null : codeByNode.get(to);
                if (pre == null || post == null) {
                    continue;
                }
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("name", "");
                r.put("preTaskCode", pre);
                r.put("preTaskVersion", 1);
                r.put("postTaskCode", post);
                r.put("postTaskVersion", 1);
                r.put("conditionType", "NONE");
                r.put("conditionParams", Map.of());
                relations.add(r);
                linked.add(to);
            }
        }
        // 无边的节点挂到虚拟根（preTaskCode=0）
        for (Map<String, Object> t : portalTasks) {
            String nk = str(t.get("nodeKey"), null);
            Long code = nk == null ? null : codeByNode.get(nk);
            if (code == null) {
                continue;
            }
            boolean hasIncoming = linked.contains(nk);
            if (!hasIncoming) {
                // 也检查 relations 里是否已有 post=code
                boolean exists = relations.stream().anyMatch(r -> code.equals(toLong(r.get("postTaskCode"))));
                if (!exists) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("name", "");
                    r.put("preTaskCode", 0);
                    r.put("preTaskVersion", 0);
                    r.put("postTaskCode", code);
                    r.put("postTaskVersion", 1);
                    r.put("conditionType", "NONE");
                    r.put("conditionParams", Map.of());
                    relations.add(r);
                }
            }
        }
        return relations;
    }

    private static String extractProcessDefinitionCode(Object data) {
        if (data == null) {
            return null;
        }
        if (data instanceof Number num) {
            return String.valueOf(num.longValue());
        }
        if (data instanceof CharSequence cs) {
            String s = cs.toString().trim();
            return StrUtil.isBlank(s) || s.startsWith("{") ? null : s;
        }
        if (data instanceof JSONObject jo) {
            Object code = jo.get("code");
            return code == null ? null : String.valueOf(code);
        }
        if (data instanceof Map<?, ?> map) {
            Object code = map.get("code");
            return code == null ? null : String.valueOf(code);
        }
        return null;
    }

    private static String sanitizeProcessName(String name) {
        String n = StrUtil.blankToDefault(name, "lh_wf").trim();
        // DS 名称不宜过长 / 含特殊字符
        n = n.replaceAll("[\\\\/:*?\"<>|]", "_");
        return n.length() > 64 ? n.substring(0, 64) : n;
    }

    private static String sanitizeTaskName(String name) {
        String n = StrUtil.blankToDefault(name, "task").trim();
        n = n.replaceAll("[\\\\/:*?\"<>|]", "_");
        return n.length() > 64 ? n.substring(0, 64) : n;
    }

    private static Long toLong(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** 兼容旧签名 */
    public Map<String, Object> createOrUpdateWorkflow(String dagId, String name, String cron, String graphJson) {
        Map<String, Object> wf = new LinkedHashMap<>();
        wf.put("workflowCode", "WF_" + StrUtil.blankToDefault(name, dagId));
        wf.put("name", name);
        wf.put("cron", cron);
        wf.put("dagId", dagId);
        wf.put("tasks", List.of());
        wf.put("taskRelation", List.of());
        wf.put("taskCount", 0);
        wf.put("edgeCount", 0);
        try {
            wf.put("legacyGraph", JSONUtil.parse(graphJson));
        } catch (Exception ignored) {
            wf.put("legacyGraphRaw", graphJson);
        }
        return createOrUpdateWorkflow(wf);
    }

    /**
     * 工作流列表
     */
    public List<Map<String, Object>> listWorkflows() {
        String projectCode = StrUtil.blankToDefault(lhProperties.getDs().getProjectCode(), "1");
        try {
            String url = trim(lhProperties.getDs().getUrl()) + "/projects/" + projectCode + "/process-definition";
            String body = executeWithAuth(() -> HttpRequest.get(url).timeout(8000));
            return List.of(Map.of("raw", body, "projectCode", projectCode));
        } catch (Exception e) {
            return List.of(Map.of("name", "demo_ods_order_cdc", "status", "ONLINE", "degraded", true,
                    "message", e.getMessage()));
        }
    }

    /**
     * 健康检查
     */
    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("component", "dolphinscheduler");
        try {
            executeWithAuth(() -> HttpRequest.get(trim(lhProperties.getDs().getUrl())).timeout(3000));
            Map<String, Object> master = checkMasterHealth();
            boolean masterOk = Boolean.TRUE.equals(master.get("ok"));
            m.put("status", masterOk ? "UP" : "DEGRADED");
            m.put("api", "UP");
            m.put("master", masterOk ? "UP" : "DOWN");
            if (!masterOk) {
                m.put("error", str(master.get("message"), "Master 不可用：可创建工作流但无法产生实例"));
            }
            return m;
        } catch (Exception e) {
            m.put("status", "DOWN");
            m.put("api", "DOWN");
            m.put("error", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
            return m;
        }
    }

    /**
     * 调度上线/下线（对应门户 prod / paused）
     *
     * @param releaseState ONLINE | OFFLINE
     */
    public Map<String, Object> setScheduleState(String workflowCode, String releaseState) {
        String code = StrUtil.blankToDefault(workflowCode, "");
        String state = StrUtil.blankToDefault(releaseState, "ONLINE").toUpperCase();
        String projectCode = StrUtil.blankToDefault(lhProperties.getDs().getProjectCode(), "1");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engine", "dolphinscheduler");
        out.put("workflowCode", code);
        out.put("releaseState", state);
        out.put("projectCode", projectCode);
        if (StrUtil.isBlank(code)) {
            out.put("ok", true);
            out.put("degraded", true);
            out.put("message", "无 ds_workflow_code，仅更新门户 status");
            return out;
        }
        String base = trim(lhProperties.getDs().getUrl());
        if (StrUtil.isBlank(base)) {
            out.put("ok", true);
            out.put("degraded", true);
            out.put("message", "lh.ds.url 未配置");
            return out;
        }
        try {
            // DS 常见：release-state；失败 soft-fail
            String url = base + "/projects/" + projectCode + "/process-definition/" + code + "/release";
            String body = executeWithAuth(() -> HttpRequest.post(url)
                    .form("releaseState", state)
                    .timeout(10000));
            out.put("ok", true);
            out.put("degraded", false);
            out.put("resp", truncate(body, 1000));
            return out;
        } catch (Exception e) {
            log.warn("DS setScheduleState soft-fail code={}: {}", code, e.getMessage());
            out.put("ok", true);
            out.put("degraded", true);
            out.put("message", e.getMessage());
            return out;
        }
    }

    /**
     * 触发一次补数/手动实例（soft-fail）
     */
    public Map<String, Object> startProcessInstance(String workflowCode, Map<String, Object> startParams) {
        String code = StrUtil.blankToDefault(workflowCode, "");
        String projectCode = StrUtil.blankToDefault(lhProperties.getDs().getProjectCode(), "1");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engine", "dolphinscheduler");
        out.put("workflowCode", code);
        out.put("projectCode", projectCode);
        if (StrUtil.isBlank(code)) {
            out.put("ok", true);
            out.put("degraded", true);
            out.put("message", "无 workflowCode，仅门户登记补数 run");
            return out;
        }
        String base = trim(lhProperties.getDs().getUrl());
        if (StrUtil.isBlank(base)) {
            out.put("ok", true);
            out.put("degraded", true);
            out.put("message", "lh.ds.url 未配置");
            return out;
        }
        try {
            // DS 3.2：processDefinitionCode 必须是数字 code（createOrUpdate 返回的 data.code）
            if (!code.chars().allMatch(Character::isDigit)) {
                Long byName = findProcessCodeByName(base, projectCode, sanitizeProcessName(code));
                if (byName == null) {
                    out.put("ok", true);
                    out.put("degraded", true);
                    out.put("message", "processDefinitionCode 非数字且未找到同名流程: " + code);
                    return out;
                }
                code = String.valueOf(byName);
                out.put("workflowCode", code);
            }
            // DS 3.2：admin 不可用 default 租户，否则 UI/启动报「未指定当前登录用户的租户」
            String workerGroup = StrUtil.blankToDefault(lhProperties.getDs().getWorkerGroup(), "default");
            String tenantCode = resolveTenantCode(base);
            Map<String, Object> form = new LinkedHashMap<>();
            form.put("processDefinitionCode", code);
            form.put("scheduleTime", "");
            form.put("failureStrategy", "CONTINUE");
            form.put("warningType", "NONE");
            form.put("warningGroupId", "0");
            form.put("execType", "START_PROCESS");
            form.put("taskDependType", "TASK_POST");
            form.put("runMode", "RUN_MODE_SERIAL");
            form.put("processInstancePriority", "MEDIUM");
            form.put("workerGroup", workerGroup);
            form.put("tenantCode", tenantCode);
            form.put("environmentCode", "-1");
            form.put("startNodeList", "");
            form.put("expectedParallelismNumber", "");
            form.put("dryRun", "0");
            form.put("testFlag", "0");
            if (startParams != null && !startParams.isEmpty()) {
                form.put("startParams", JSONUtil.toJsonStr(startParams));
            } else {
                form.put("startParams", "");
            }
            // 启动前确保 ONLINE（否则也会 50014）
            tryReleaseState(base, projectCode, code, "ONLINE");
            String url = base + "/projects/" + projectCode + "/executors/start-process-instance";
            String body = executeWithAuth(() -> HttpRequest.post(url)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .form(form)
                    .timeout(15000));
            out.put("ok", true);
            out.put("degraded", false);
            out.put("resp", truncate(body, 1000));
            try {
                JSONObject jo = JSONUtil.parseObj(body);
                if (jo.containsKey("code") && jo.getInt("code", 0) != 0) {
                    out.put("degraded", true);
                    out.put("message", jo.getStr("msg", "DS 返回非 0 code"));
                    return out;
                }
                Object data = jo.get("data");
                String parsedId = extractProcessInstanceId(data);
                if (StrUtil.isBlank(parsedId)) {
                    parsedId = firstNonBlank(jo.getStr("processInstanceId"), jo.getStr("id"));
                }
                if (StrUtil.isNotBlank(parsedId)) {
                    out.put("processInstanceId", parsedId);
                    out.put("startToken", parsedId);
                    // DS 3.2 start 返回的 data 常是 command 雪花码，不是 process_instance.id；
                    // GET /process-instances/{snowflake} 会 10116。改为列表回查真实实例 id。
                    String realId = resolveStartedProcessInstanceId(base, projectCode, code, parsedId);
                    if (StrUtil.isNotBlank(realId)) {
                        out.put("processInstanceId", realId);
                        out.put("degraded", false);
                        out.remove("masterLikelyDown");
                    } else if (!verifyProcessInstanceExists(base, projectCode, parsedId)) {
                        // 列表也找不到：可能排队中；不武断判定 Master 宕机（/monitor/masters 本环境常 10045）
                        out.put("degraded", false);
                        out.put("message", "DS 已受理启动(token=" + parsedId
                                + ")，暂未回查到 process_instance.id；请到 DS 工作流实例列表确认。"
                                + "（本环境 start 返回值多为 command 码而非实例主键）");
                        out.put("pendingVerify", true);
                    }
                } else {
                    out.put("degraded", true);
                    out.put("message", "DS 启动成功但未返回 processInstanceId");
                }
            } catch (Exception ignored) {
                // raw body already in resp
            }
            return out;
        } catch (Exception e) {
            log.warn("DS startProcessInstance soft-fail code={}: {}", code, e.getMessage());
            out.put("ok", true);
            out.put("degraded", true);
            out.put("message", e.getMessage());
            return out;
        }
    }

    /**
     * 启动后回读实例：Master 宕机时 start 仍可能返回雪花 id，但 GET 实例会 10116 / 列表为空。
     */
    private boolean verifyProcessInstanceExists(String base, String projectCode, String processInstanceId) {
        if (StrUtil.isBlank(processInstanceId)) {
            return false;
        }
        try {
            String url = base + "/projects/" + projectCode + "/process-instances/" + processInstanceId;
            String body = executeWithAuth(() -> HttpRequest.get(url).timeout(10000));
            JSONObject jo = JSONUtil.parseObj(body);
            if (jo.getInt("code", -1) != 0) {
                return false;
            }
            return jo.get("data") != null;
        } catch (Exception e) {
            log.debug("DS verify instance {} soft-fail: {}", processInstanceId, e.getMessage());
            return false;
        }
    }

    /**
     * DS 3.2：start-process-instance 的 data 多为 command 雪花码；真实实例主键是自增 id。
     * 通过 processDefineCode 列表回查最近一条 RUNNING/刚创建的实例。
     */
    private String resolveStartedProcessInstanceId(
            String base, String projectCode, String processDefinitionCode, String startToken) {
        if (StrUtil.isBlank(processDefinitionCode)) {
            return null;
        }
        // 直接 GET token：少数版本可能返回真 id
        if (verifyProcessInstanceExists(base, projectCode, startToken)) {
            return startToken;
        }
        long deadline = System.currentTimeMillis() + 8000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            try {
                String url = base + "/projects/" + projectCode + "/process-instances"
                        + "?pageNo=1&pageSize=10&processDefineCode=" + processDefinitionCode
                        + "&stateType=&searchVal=&startDate=&endDate=";
                String body = executeWithAuth(() -> HttpRequest.get(url).timeout(10000));
                JSONObject jo = JSONUtil.parseObj(body);
                if (jo.getInt("code", -1) != 0) {
                    continue;
                }
                Object data = jo.get("data");
                Object list = data instanceof JSONObject d ? d.get("totalList") : null;
                if (!(list instanceof Iterable<?> it)) {
                    continue;
                }
                for (Object o : it) {
                    if (!(o instanceof JSONObject row)) {
                        continue;
                    }
                    String id = firstNonBlank(row.getStr("id"),
                            row.get("id") == null ? null : String.valueOf(row.get("id")));
                    if (StrUtil.isBlank(id)) {
                        continue;
                    }
                    // 优先 RUNNING；否则取列表第一条（按时间倒序）
                    String state = StrUtil.blankToDefault(row.getStr("state"), "");
                    if (state.contains("RUNNING") || state.contains("SUBMITTED")
                            || state.contains("READY") || StrUtil.isBlank(state)) {
                        log.info("DS resolved processInstanceId={} from list (startToken={})", id, startToken);
                        return id;
                    }
                    // 已结束也算启动成功
                    log.info("DS resolved finished processInstanceId={} from list (startToken={})", id, startToken);
                    return id;
                }
            } catch (Exception e) {
                log.debug("resolveStartedProcessInstanceId soft-fail: {}", e.getMessage());
            }
        }
        return null;
    }

    /** 探测 Master 是否在注册中心可见（失败不抛） */
    public Map<String, Object> checkMasterHealth() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("component", "dolphinscheduler-master");
        String base = trim(lhProperties.getDs().getUrl());
        if (StrUtil.isBlank(base)) {
            out.put("ok", false);
            out.put("message", "lh.ds.url 未配置");
            return out;
        }
        try {
            String body = executeWithAuth(() -> HttpRequest.get(base + "/monitor/masters").timeout(8000));
            JSONObject jo = JSONUtil.parseObj(body);
            boolean ok = jo.getInt("code", -1) == 0 && jo.get("data") != null;
            out.put("ok", ok);
            out.put("degraded", !ok);
            out.put("resp", truncate(body, 500));
            if (!ok) {
                out.put("message", jo.getStr("msg", "list masters error — Master 可能未启动或未注册"));
            }
            return out;
        } catch (Exception e) {
            out.put("ok", false);
            out.put("degraded", true);
            out.put("message", e.getMessage());
            return out;
        }
    }

    /**
     * 查询流程实例状态（soft-fail）
     */
    public Map<String, Object> getProcessInstance(String processInstanceId) {
        String id = StrUtil.blankToDefault(processInstanceId, "");
        String projectCode = StrUtil.blankToDefault(lhProperties.getDs().getProjectCode(), "1");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engine", "dolphinscheduler");
        out.put("processInstanceId", id);
        out.put("projectCode", projectCode);
        if (StrUtil.isBlank(id)) {
            out.put("ok", false);
            out.put("degraded", true);
            out.put("message", "无 processInstanceId");
            return out;
        }
        String base = trim(lhProperties.getDs().getUrl());
        if (StrUtil.isBlank(base)) {
            out.put("ok", false);
            out.put("degraded", true);
            out.put("message", "lh.ds.url 未配置");
            return out;
        }
        try {
            String url = base + "/projects/" + projectCode + "/process-instances/" + id;
            String body = executeWithAuth(() -> HttpRequest.get(url).timeout(10000));
            out.put("ok", true);
            out.put("degraded", false);
            out.put("resp", truncate(body, 2000));
            JSONObject jo = JSONUtil.parseObj(body);
            Object data = jo.get("data");
            if (data instanceof JSONObject dataObj) {
                String state = firstNonBlank(dataObj.getStr("state"), dataObj.getStr("status"));
                out.put("state", state);
                out.put("name", dataObj.getStr("name"));
                out.put("startTime", dataObj.getStr("startTime"));
                out.put("endTime", dataObj.getStr("endTime"));
            }
            return out;
        } catch (Exception e) {
            log.warn("DS getProcessInstance soft-fail id={}: {}", id, e.getMessage());
            out.put("ok", false);
            out.put("degraded", true);
            out.put("message", e.getMessage());
            return out;
        }
    }

    /**
     * 查询流程实例下任务实例列表（soft-fail），用于按节点回写 run_node。
     */
    public Map<String, Object> listTaskInstances(String processInstanceId) {
        String id = StrUtil.blankToDefault(processInstanceId, "");
        String projectCode = StrUtil.blankToDefault(lhProperties.getDs().getProjectCode(), "1");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engine", "dolphinscheduler");
        out.put("processInstanceId", id);
        out.put("projectCode", projectCode);
        if (StrUtil.isBlank(id)) {
            out.put("ok", false);
            out.put("degraded", true);
            out.put("message", "无 processInstanceId");
            return out;
        }
        String base = trim(lhProperties.getDs().getUrl());
        if (StrUtil.isBlank(base)) {
            out.put("ok", false);
            out.put("degraded", true);
            out.put("message", "lh.ds.url 未配置");
            return out;
        }
        try {
            // DS 3.x 常见路径
            String url = base + "/projects/" + projectCode + "/process-instances/" + id + "/tasks";
            String body = executeWithAuth(() -> HttpRequest.get(url).timeout(12000));
            out.put("ok", true);
            out.put("degraded", false);
            out.put("resp", truncate(body, 3000));
            List<Map<String, Object>> tasks = new java.util.ArrayList<>();
            JSONObject jo = JSONUtil.parseObj(body);
            Object data = jo.get("data");
            if (data instanceof cn.hutool.json.JSONArray arr) {
                for (Object o : arr) {
                    if (o instanceof JSONObject t) {
                        tasks.add(parseTaskInstance(t));
                    }
                }
            } else if (data instanceof JSONObject dataObj) {
                Object list = dataObj.get("taskList");
                if (list == null) {
                    list = dataObj.get("totalList");
                }
                if (list instanceof cn.hutool.json.JSONArray arr2) {
                    for (Object o : arr2) {
                        if (o instanceof JSONObject t) {
                            tasks.add(parseTaskInstance(t));
                        }
                    }
                }
            }
            out.put("tasks", tasks);
            out.put("taskCount", tasks.size());
            return out;
        } catch (Exception e) {
            log.warn("DS listTaskInstances soft-fail id={}: {}", id, e.getMessage());
            out.put("ok", false);
            out.put("degraded", true);
            out.put("message", e.getMessage());
            out.put("tasks", List.of());
            return out;
        }
    }

    private static Map<String, Object> parseTaskInstance(JSONObject t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", firstNonBlank(t.getStr("id"), t.getStr("taskInstanceId")));
        m.put("name", firstNonBlank(t.getStr("name"), t.getStr("taskName")));
        m.put("state", firstNonBlank(t.getStr("state"), t.getStr("status")));
        m.put("host", t.getStr("host"));
        m.put("startTime", t.getStr("startTime"));
        m.put("endTime", t.getStr("endTime"));
        m.put("logPath", t.getStr("logPath"));
        return m;
    }

    /**
     * 解析租户：优先配置 {@code lh.ds.tenant-code}；否则读登录用户绑定租户。
     * <p>本环境 admin 绑定 {@code root}。勿对 admin 使用 {@code default}，
     * 否则 UI/启动会报「未指定当前登录用户的租户」。</p>
     * <p>DS 3.2 的 process-definition 创建表单本身不持久化 tenantCode；租户只在
     * start / schedule 生效。此处仍写入 form 以便兼容部分发行版，并以用户绑定为准。</p>
     */
    private String resolveTenantCode(String base) {
        String configured = StrUtil.trim(lhProperties.getDs().getTenantCode());
        if (StrUtil.isNotBlank(configured) && !"default".equalsIgnoreCase(configured)) {
            return configured;
        }
        long now = System.currentTimeMillis();
        if (StrUtil.isNotBlank(cachedTenantCode) && now - cachedTenantAtMs < SESSION_TTL_MS) {
            return cachedTenantCode;
        }
        try {
            String body = executeWithAuth(() -> HttpRequest.get(base + "/users/get-user-info").timeout(8000));
            JSONObject jo = JSONUtil.parseObj(body);
            Object data = jo.get("data");
            String fromUser = null;
            Integer tenantId = null;
            if (data instanceof JSONObject dataObj) {
                fromUser = firstNonBlank(dataObj.getStr("tenantCode"), null);
                tenantId = dataObj.getInt("tenantId");
            }
            if (StrUtil.isNotBlank(fromUser) && !"default".equalsIgnoreCase(fromUser)) {
                cachedTenantCode = fromUser.trim();
                cachedTenantAtMs = now;
                return cachedTenantCode;
            }
            // tenantId>0 但 code 空：按租户列表反查
            if (tenantId != null && tenantId > 0) {
                String byId = lookupTenantCodeById(base, tenantId);
                if (StrUtil.isNotBlank(byId) && !"default".equalsIgnoreCase(byId)) {
                    cachedTenantCode = byId.trim();
                    cachedTenantAtMs = now;
                    return cachedTenantCode;
                }
            }
            log.warn("DS 登录用户未绑定有效租户(tenantCode={}, tenantId={})，回退 root；"
                    + "请在 DS 安全中心为该用户绑定租户并重新登录 UI", fromUser, tenantId);
        } catch (Exception e) {
            log.debug("resolveTenantCode soft-fail: {}", e.getMessage());
        }
        // 最终兜底 root（与本环境 admin 绑定一致）
        return StrUtil.blankToDefault(configured, "root");
    }

    private String lookupTenantCodeById(String base, int tenantId) {
        try {
            String body = executeWithAuth(() -> HttpRequest.get(base + "/tenants/list").timeout(8000));
            JSONObject jo = JSONUtil.parseObj(body);
            Object data = jo.get("data");
            if (data instanceof Iterable<?> it) {
                for (Object o : it) {
                    if (!(o instanceof JSONObject row)) {
                        continue;
                    }
                    if (tenantId == row.getInt("id", -999)) {
                        return row.getStr("tenantCode");
                    }
                }
            }
        } catch (Exception e) {
            log.debug("lookupTenantCodeById soft-fail: {}", e.getMessage());
        }
        return null;
    }

    private String executeWithAuth(java.util.function.Supplier<HttpRequest> requestFactory) {
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            boolean forceLogin = attempt > 0;
            if (forceLogin) {
                cachedSessionToken = null;
                cachedSessionAtMs = 0L;
                log.warn("DS 401，login 刷新 session 后重试");
            }
            try {
                cn.hutool.http.HttpResponse resp = withSession(
                        requestFactory.get(), resolveSessionToken(forceLogin))
                        .execute();
                int status = resp.getStatus();
                String body = resp.body();
                if (isUnauthorizedStatus(status, body)) {
                    throw new IllegalStateException("Error request, response status: 401"
                            + (StrUtil.isBlank(body) ? "" : " body=" + truncate(body, 200)));
                }
                return body;
            } catch (Exception e) {
                last = e;
                if (attempt == 0 && isUnauthorized(e)) {
                    continue;
                }
                if (e instanceof RuntimeException re) {
                    throw re;
                }
                throw new IllegalStateException(e);
            }
        }
        if (last instanceof RuntimeException re) {
            throw re;
        }
        throw new IllegalStateException(last);
    }

    /**
     * 本环境 DS 3.2：只认 {@code sessionId} 头 / Cookie。
     * <b>切勿</b>再带 {@code token} 头——实测即使 sessionId 正确，附带 token 也会整请求 401。
     */
    private static HttpRequest withSession(HttpRequest req, String sessionId) {
        return req.header("sessionId", sessionId)
                .header("Cookie", "sessionId=" + sessionId);
    }

    /**
     * 解析会话：优先 user/password 登录取 sessionId。
     * Vault/yml 里的 token 仅当像 UUID session 时才可用；假值 local/过期 PAT 一律忽略。
     */
    private synchronized String resolveSessionToken(boolean forceLogin) {
        if (!forceLogin && StrUtil.isNotBlank(cachedSessionToken)
                && System.currentTimeMillis() - cachedSessionAtMs < SESSION_TTL_MS) {
            return cachedSessionToken;
        }
        Map<String, String> cred = credentialResolver.ds();
        String user = StrUtil.trim(cred.get("username"));
        String pass = cred.get("password");
        // 本环境 DS 必须 login 换 sessionId；配置里的 token 头不可用且易 401
        if (StrUtil.isNotBlank(user) && StrUtil.isNotBlank(pass)) {
            String sessionId = loginForSessionId(user, pass);
            cachedSessionToken = sessionId;
            cachedSessionAtMs = System.currentTimeMillis();
            return cachedSessionToken;
        }
        String configured = StrUtil.trim(cred.get("token"));
        if (isUsableSessionId(configured)) {
            cachedSessionToken = configured;
            cachedSessionAtMs = System.currentTimeMillis();
            return cachedSessionToken;
        }
        throw new IllegalStateException("DolphinScheduler username/password 为空，无法 login 取 sessionId（请查 Vault "
                + StrUtil.blankToDefault(lhProperties.getDs().getVaultPath(), "platform/dolphinscheduler/api")
                + " 或 lh.ds.user/password）");
    }

    private String loginForSessionId(String user, String pass) {
        String base = trim(lhProperties.getDs().getUrl());
        if (StrUtil.isBlank(base)) {
            throw new IllegalStateException("lh.ds.url 未配置，无法登录 DS");
        }
        // DS 3.x：POST /login form userName + userPassword → data.sessionId
        String body = HttpRequest.post(base + "/login")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .form("userName", user)
                .form("userPassword", pass)
                .timeout(15000)
                .execute()
                .body();
        JSONObject jo = JSONUtil.parseObj(body);
        if (jo.containsKey("code") && jo.getInt("code", -1) != 0) {
            throw new IllegalStateException("DS 登录失败: " + jo.getStr("msg", truncate(body, 200)));
        }
        Object data = jo.get("data");
        String sessionId = null;
        if (data instanceof JSONObject dataObj) {
            sessionId = firstNonBlank(dataObj.getStr("sessionId"), dataObj.getStr("token"));
        } else if (data instanceof Map<?, ?> map) {
            Object sid = map.get("sessionId");
            if (sid == null) {
                sid = map.get("token");
            }
            sessionId = sid == null ? null : String.valueOf(sid);
        }
        if (StrUtil.isBlank(sessionId)) {
            sessionId = firstNonBlank(jo.getStr("sessionId"), jo.getStr("token"));
        }
        if (StrUtil.isBlank(sessionId)) {
            throw new IllegalStateException("DS 登录响应无 sessionId: " + truncate(body, 300));
        }
        sessionId = sessionId.trim();
        // 先写入 session 缓存，再解析租户（避免 resolveTenantCode→executeWithAuth 递归登录）
        cachedSessionToken = sessionId;
        cachedSessionAtMs = System.currentTimeMillis();
        cachedTenantCode = null;
        cachedTenantAtMs = 0L;
        try {
            String tenant = resolveTenantCode(base);
            log.info("DS login ok user={} tenantCode={}", user, tenant);
        } catch (Exception e) {
            log.warn("DS login ok user={} 但解析租户失败: {}", user, e.getMessage());
        }
        return sessionId;
    }

    /** 仅接受像 DS sessionId 的值（UUID）；拒绝 local / 短假值 / JWT PAT */
    private static boolean isUsableSessionId(String token) {
        if (StrUtil.isBlank(token)) {
            return false;
        }
        String t = token.trim();
        if ("local".equalsIgnoreCase(t) || "none".equalsIgnoreCase(t) || "null".equalsIgnoreCase(t)) {
            return false;
        }
        // DS 登录返回 UUID；JWT（含点）不当作 session
        if (t.contains(".")) {
            return false;
        }
        return t.length() >= 32;
    }

    private static boolean isUnauthorizedStatus(int status, String body) {
        if (status == 401 || status == 403) {
            return true;
        }
        String b = StrUtil.blankToDefault(body, "");
        return b.contains("401") && (StrUtil.containsIgnoreCase(b, "unauthorized")
                || StrUtil.containsIgnoreCase(b, "Error request")
                || StrUtil.containsIgnoreCase(b, "未授权")
                || StrUtil.containsIgnoreCase(b, "login"));
    }

    private static boolean isUnauthorized(Throwable e) {
        if (e == null) {
            return false;
        }
        String msg = StrUtil.blankToDefault(e.getMessage(), "");
        if (msg.contains("401") || StrUtil.containsIgnoreCase(msg, "unauthorized")
                || StrUtil.containsIgnoreCase(msg, "未授权")) {
            return true;
        }
        Throwable c = e.getCause();
        return c != null && c != e && isUnauthorized(c);
    }

    /** DS start-process-instance 等接口要求 form；嵌套 Map 序列化为 JSON 字符串 */
    private static Map<String, Object> toFormMap(Map<String, Object> payload) {
        Map<String, Object> form = new LinkedHashMap<>();
        if (payload == null) {
            return form;
        }
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            Object v = e.getValue();
            if (v == null) {
                continue;
            }
            if (v instanceof Map || v instanceof List) {
                form.put(e.getKey(), JSONUtil.toJsonStr(v));
            } else {
                form.put(e.getKey(), v);
            }
        }
        return form;
    }

    private String trim(String url) {
        return url == null ? "" : (url.endsWith("/") ? url.substring(0, url.length() - 1) : url);
    }

    private static String str(Object o, String def) {
        if (o == null) {
            return def;
        }
        String s = String.valueOf(o);
        return StrUtil.isBlank(s) || "null".equals(s) ? def : s;
    }

    /**
     * FLINK SQL：保留占位符的 rawScript / lhSql（凭证不在此解析）。
     */
    private static String pickFlinkSql(Map<String, Object> raw, String sqlFromLh, String rawScript) {
        if (StrUtil.isNotBlank(rawScript) && !rawScript.trim().startsWith("#!")) {
            return rawScript.trim();
        }
        String lh = str(raw.get("lhSql"), null);
        if (StrUtil.isNotBlank(lh)) {
            return lh.trim();
        }
        if (StrUtil.isNotBlank(sqlFromLh)) {
            return sqlFromLh.trim();
        }
        return "SELECT 1";
    }

    private static String extractProcessInstanceId(Object data) {
        if (data == null) {
            return null;
        }
        if (data instanceof Number num) {
            return String.valueOf(num.longValue());
        }
        if (data instanceof CharSequence cs) {
            String s = cs.toString().trim();
            return StrUtil.isBlank(s) || s.startsWith("{") || s.startsWith("[") ? null : s;
        }
        if (data instanceof JSONObject dataObj) {
            return firstNonBlank(
                    dataObj.getStr("id"),
                    dataObj.getStr("processInstanceId"),
                    dataObj.getStr("processInstanceCode"),
                    dataObj.getStr("code"),
                    dataObj.get("id") == null ? null : String.valueOf(dataObj.get("id")),
                    dataObj.get("processInstanceId") == null ? null : String.valueOf(dataObj.get("processInstanceId")));
        }
        if (data instanceof Map<?, ?> map) {
            Object id = map.get("id");
            if (id == null) {
                id = map.get("processInstanceId");
            }
            if (id == null) {
                id = map.get("processInstanceCode");
            }
            return id == null ? null : String.valueOf(id);
        }
        return null;
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (StrUtil.isNotBlank(v)) {
                return v.trim();
            }
        }
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
