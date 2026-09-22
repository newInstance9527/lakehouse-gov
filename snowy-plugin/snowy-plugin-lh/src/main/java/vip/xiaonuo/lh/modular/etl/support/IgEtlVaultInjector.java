package vip.xiaonuo.lh.modular.etl.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.engine.DataxJobBuilder;
import vip.xiaonuo.lh.core.engine.DsTaskScriptBuilder;
import vip.xiaonuo.lh.core.engine.FlinkSourceSqlCompiler;
import vip.xiaonuo.lh.core.engine.IgEtlNodeTypes;
import vip.xiaonuo.lh.core.engine.LhStagingTables;
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;
import vip.xiaonuo.lh.modular.datasource.mapper.LhDatasourceMapper;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 发布时按节点 conf.dsId 解析 Vault，注入 DS Workflow 任务参数（不写明文密码进投影）。
 * <p>补齐：列元数据 → Flink CDC DDL；DataX 读写端 {@code LH_READER_*} / {@code LH_WRITER_*} 拆分。</p>
 */
@Component
public class IgEtlVaultInjector {

    private static final Logger log = LoggerFactory.getLogger(IgEtlVaultInjector.class);

    private static final Set<String> SECRET_KEYS = Set.of(
            "password", "passwd", "secret", "secretKey", "accessKey", "token", "apiKey", "authToken");

    @Resource
    private LhDatasourceMapper datasourceMapper;
    @Resource
    private LhVaultClient vaultClient;
    @Resource
    private LhProperties lhProperties;
    @Resource
    private LhTableColumnFetcher columnFetcher;

    /**
     * @return 摘要：injected / missing / skipped
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> enrichWorkflow(Map<String, Object> workflow, List<IgEtlNode> nodes) {
        int injected = 0;
        int missingDs = 0;
        int missingVault = 0;
        int noDsId = 0;
        int columnsFilled = 0;
        List<String> notes = new ArrayList<>();

        Object tasksObj = workflow.get("tasks");
        if (!(tasksObj instanceof List<?> tasks) || tasks.isEmpty()) {
            return Map.of("injected", 0, "skipped", true, "reason", "no tasks");
        }

        Map<String, IgEtlNode> byKey = new LinkedHashMap<>();
        for (IgEtlNode n : nodes) {
            byKey.put(n.getNodeKey(), n);
        }

        String olUrl = resolveOpenLineageUrl();

        for (Object t : tasks) {
            if (!(t instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> task = (Map<String, Object>) raw;
            String nodeKey = String.valueOf(task.get("nodeKey"));
            IgEtlNode node = byKey.get(nodeKey);
            if (node == null) {
                continue;
            }
            Object tpObj = task.get("taskParams");
            Map<String, Object> taskParams;
            if (tpObj instanceof Map<?, ?> tp) {
                taskParams = (Map<String, Object>) tp;
            } else {
                taskParams = new LinkedHashMap<>();
                task.put("taskParams", taskParams);
            }

            if (StrUtil.isNotBlank(olUrl)) {
                Map<String, Object> env = envMap(taskParams);
                env.putIfAbsent("OPENLINEAGE_URL", olUrl);
                taskParams.put("env", env);
            }
            injectPlatformEngineEnv(taskParams);

            JSONObject conf = parseConf(node.getConfJson());
            String nodeType = StrUtil.blankToDefault(node.getNodeType(), "");
            boolean sinkSide = nodeType.startsWith("sink_");

            // 上游 ds / 表（DataX 双源、clean 上游）
            String upstreamDsId = firstNonBlank(
                    str(taskParams.get("lhUpstreamDsId")),
                    conf.getStr("readerDsId"),
                    conf.getStr("srcDsId"),
                    conf.getStr("_lhUpstreamDsId"));
            String upstreamTable = firstNonBlank(
                    str(taskParams.get("lhUpstreamTable")),
                    conf.getStr("_lhUpstreamTable"));
            if (StrUtil.isNotBlank(upstreamDsId)) {
                conf.set("_lhUpstreamDsId", upstreamDsId);
                taskParams.put("lhUpstreamDsId", upstreamDsId);
            }
            if (StrUtil.isNotBlank(upstreamTable)) {
                conf.set("_lhUpstreamTable", upstreamTable);
            }
            String landingTable = firstNonBlank(
                    str(taskParams.get("lhLandingTable")),
                    conf.getStr("_lhLandingTable"));
            String cleanTable = firstNonBlank(
                    str(taskParams.get("lhCleanTable")),
                    conf.getStr("_lhCleanTable"));
            if (StrUtil.isNotBlank(landingTable)) {
                conf.set("_lhLandingTable", landingTable);
                conf.set("_lhBoundedChain", true);
            }
            if (StrUtil.isNotBlank(cleanTable)) {
                conf.set("_lhCleanTable", cleanTable);
            }

            String dsId = conf.getStr("dsId");
            boolean icebergSink = "sink_iceberg".equals(nodeType);
            // clean/transform 常无本节点 dsId：用上游源库读写 lh_ods_*/lh_clean_*
            if (StrUtil.isBlank(dsId) && ("clean".equals(nodeType) || "transform".equals(nodeType)
                    || "mapping".equals(nodeType))) {
                dsId = upstreamDsId;
            }
            String ticketNo = conf.getStr("ticketNo");
            if (StrUtil.isNotBlank(ticketNo)) {
                taskParams.put("lhTicketNo", ticketNo.trim());
            }
            if (StrUtil.isBlank(dsId)) {
                noDsId++;
                // Iceberg 汇可只配 catalog.schema.table，读端走上游 JDBC 落表
                if (!icebergSink) {
                    continue;
                }
            } else {
                taskParams.put("lhDsId", dsId);
            }

            LhDatasource ds = StrUtil.isBlank(dsId) ? null : datasourceMapper.selectById(dsId);
            if (StrUtil.isNotBlank(dsId) && ds == null) {
                missingDs++;
                notes.add(nodeKey + ": dsId 不存在 " + dsId);
                if (!icebergSink) {
                    continue;
                }
            }
            if (ds != null) {
                fillDsIntoConf(ds, conf, taskParams, sinkSide ? "writer" : "primary", nodeType);
            }

            // 上游数据源（汇节点 DataX 读端）
            LhDatasource readerDs = null;
            String readerDsId = sinkSide
                    ? firstNonBlank(conf.getStr("readerDsId"), conf.getStr("srcDsId"), upstreamDsId)
                    : dsId;
            String writerDsId = sinkSide
                    ? dsId
                    : firstNonBlank(conf.getStr("writerDsId"), conf.getStr("targetDsId"));
            if (StrUtil.isNotBlank(readerDsId) && (dsId == null || !readerDsId.equals(dsId))) {
                readerDs = datasourceMapper.selectById(readerDsId);
                if (readerDs != null) {
                    conf.set("lhUpstreamDsType", readerDs.getType());
                    conf.set("lhReaderDsType", readerDs.getType());
                    conf.set("_lhUpstreamDsId", readerDsId);
                    taskParams.put("lhReaderDsId", readerDsId);
                    taskParams.put("lhReaderDsType", readerDs.getType());
                    taskParams.put("lhUpstreamDsId", readerDsId);
                    if (icebergSink) {
                        fillReaderConn(readerDs, conf, taskParams);
                    }
                }
            } else if (sinkSide && StrUtil.isNotBlank(readerDsId) && readerDsId.equals(dsId)) {
                // 汇点 reader 不能与写端同 ds（否则 mysqlreader 会吃到 postgres URL）
                notes.add(nodeKey + ": sink readerDsId 与写端 dsId 相同，忽略并等待上游解析");
                readerDsId = null;
            } else if (!sinkSide) {
                readerDs = ds;
                readerDsId = dsId;
            }
            if (sinkSide && ds != null) {
                conf.set("lhWriterDsType", ds.getType());
                taskParams.put("lhWriterDsId", dsId);
            } else if (StrUtil.isNotBlank(writerDsId)) {
                LhDatasource wds = datasourceMapper.selectById(writerDsId);
                if (wds != null) {
                    conf.set("lhWriterDsType", wds.getType());
                    conf.set("targetDsType", wds.getType());
                    taskParams.put("lhWriterDsId", writerDsId);
                }
            }

            String engine = String.valueOf(taskParams.get("lhEngine"));
            if (StrUtil.isNotBlank(engine) && !"null".equals(engine)) {
                conf.set("_lhEngine", engine);
            }
            if ("SQL".equals(String.valueOf(task.get("taskType")))) {
                taskParams.put("type", DsTaskScriptBuilder.mapDsSqlType(conf));
            }

            // 列元数据：源节点按本 ds；汇 DataX 按读端
            String colDsId = IgEtlNodeTypes.SOURCE.contains(nodeType) ? dsId : readerDsId;
            String colTable = IgEtlNodeTypes.SOURCE.contains(nodeType)
                    ? firstNonBlank(conf.getStr("table"), conf.getStr("src"), firstTable(conf))
                    : firstNonBlank(upstreamTable, conf.getStr("src"), conf.getStr("table"));
            if (StrUtil.isNotBlank(colDsId) && StrUtil.isNotBlank(colTable)) {
                List<FlinkSourceSqlCompiler.Column> cols = columnFetcher.fetch(colDsId, colTable, conf.getStr("pk"));
                if (cols.isEmpty()) {
                    cols = FlinkSourceSqlCompiler.columnsFromConf(conf);
                }
                if (!cols.isEmpty()) {
                    // 勿 conf.set(_lhColumnObjs, Column)：Hutool 会把 record 收成 JSONObject
                    taskParams.put("_lhStagingColumns", cols);
                    JSONArray arr = new JSONArray();
                    for (FlinkSourceSqlCompiler.Column c : cols) {
                        JSONObject o = new JSONObject();
                        o.set("name", c.name());
                        o.set("flinkType", c.flinkType());
                        o.set("pk", c.primaryKey());
                        arr.add(o);
                    }
                    conf.set("_lhColumns", arr);
                    taskParams.put("lhColumnCount", cols.size());
                    columnsFilled++;
                    // 源→清洗→入库：预建 lh_ods_* / lh_clean_*（Flink JDBC sink 不建物理表）
                    if (IgEtlNodeTypes.SOURCE.contains(nodeType) && StrUtil.isNotBlank(landingTable)) {
                        String jdbcUrl = firstNonBlank(conf.getStr("lhJdbcUrl"), conf.getStr("jdbcUrl"));
                        String u = firstNonBlank(conf.getStr("lhJdbcUser"), conf.getStr("user"));
                        String pw = firstNonBlank(conf.getStr("lhJdbcPassword"), conf.getStr("password"));
                        // secret 尚未读：后面 Vault 段再 ensure 一次；此处有明文则先建
                        if (StrUtil.isNotBlank(jdbcUrl) && StrUtil.isNotBlank(u)) {
                            LhStagingDdlEnsured.ensureMysqlFamily(ds, jdbcUrl, u, pw,
                                    conf.getStr("database"), LhStagingTables.bareTable(colTable), cols);
                            taskParams.put("_lhStagingColsReady", true);
                        }
                    }
                }
            }

            boolean datax = "datax".equalsIgnoreCase(engine) || taskParams.containsKey("lhDataxJob")
                    || "DATAX".equals(String.valueOf(task.get("taskType")));
            boolean flinkOrSpark = "flink".equalsIgnoreCase(engine) || "spark".equalsIgnoreCase(engine)
                    || "FLINK".equals(String.valueOf(task.get("taskType")))
                    || "SPARK".equals(String.valueOf(task.get("taskType")));

            if (datax) {
                taskParams.put("lhDataxJob", DataxJobBuilder.buildJob(node, conf));
                taskParams.put("rawScript", DataxJobBuilder.buildShell(node, conf));
            }
            if (flinkOrSpark && (IgEtlNodeTypes.SOURCE.contains(nodeType)
                    || "clean".equals(nodeType)
                    || "transform".equals(nodeType)
                    || "mapping".equals(nodeType)
                    || "sink_rdb".equals(nodeType) || "sink_ck".equals(nodeType)
                    || icebergSink)) {
                String rebuilt = DsTaskScriptBuilder.resolveSql(node, conf);
                taskParams.put("lhSql", rebuilt);
                if ("spark".equalsIgnoreCase(engine) || "SPARK".equals(String.valueOf(task.get("taskType")))) {
                    String master = firstNonBlank(conf.getStr("master"), conf.getStr("sparkMaster"), "local[*]");
                    boolean yarn = master.toLowerCase(java.util.Locale.ROOT).contains("yarn");
                    if (yarn) {
                        // 后续切 YARN：原生 SPARK，DS 可跟踪 application_*
                        task.put("taskType", "SPARK");
                        taskParams.put("rawScript", rebuilt);
                        taskParams.put("programType", "SQL");
                        taskParams.put("sqlExecutionType", "SCRIPT");
                        taskParams.put("deployMode", firstNonBlank(conf.getStr("deployMode"), "client"));
                        taskParams.put("master", "yarn");
                        taskParams.remove("lhPreferShell");
                    } else {
                        // local / spark:// ：SHELL + spark-sql，退出码推进下游
                        task.put("taskType", "SHELL");
                        taskParams.put("programType", "SQL");
                        taskParams.put("sqlExecutionType", "SCRIPT");
                        taskParams.put("lhSparkSql", rebuilt);
                        taskParams.put("lhPreferShell", true);
                        taskParams.put("rawScript",
                                vip.xiaonuo.lh.core.engine.SparkSubmitBuilder.buildSqlShell(
                                        nodeKey, rebuilt, master));
                    }
                } else {
                    // 保持 DS 原生 FLINK + programType=SQL（勿压成 SHELL）
                    task.put("taskType", "FLINK");
                    taskParams.put("programType", "SQL");
                    taskParams.remove("lhPreferShell");
                    String flinkSql = rebuilt.endsWith(";") ? rebuilt : rebuilt + ";";
                    taskParams.put("rawScript", flinkSql);
                    taskParams.put("lhFlinkRestScript",
                            vip.xiaonuo.lh.core.engine.FlinkSubmitBuilder.buildShell(node, rebuilt, conf));
                }
            }

            // Vault：写端 ds；Iceberg 无写端 ds 时用上游读端 JDBC
            String vaultPath = ds == null ? null : ds.getVaultPath();
            Map<String, Object> secret = null;
            if (StrUtil.isBlank(vaultPath) && icebergSink && readerDs != null
                    && StrUtil.isNotBlank(readerDs.getVaultPath())) {
                vaultPath = readerDs.getVaultPath();
                secret = vaultClient.readOrEmpty(vaultPath);
            } else if (StrUtil.isBlank(vaultPath)) {
                missingVault++;
                notes.add(nodeKey + ": 数据源无 vaultPath");
                if (!icebergSink) {
                    continue;
                }
            } else {
                taskParams.put("lhVaultPath", vaultPath);
                taskParams.put("secretRef", "vault://" + vaultPath);
                secret = vaultClient.readOrEmpty(vaultPath);
                if (secret == null || secret.isEmpty()) {
                    missingVault++;
                    notes.add(nodeKey + ": Vault 空 " + vaultPath);
                    if (!icebergSink) {
                        continue;
                    }
                    secret = null;
                }
            }
            if (icebergSink && StrUtil.isNotBlank(vaultPath) && secret != null) {
                taskParams.put("lhVaultPath", vaultPath);
                taskParams.put("secretRef", "vault://" + vaultPath);
            }

            // Flink/Spark/DataX：脚本/JSON 保留 ${LH_JDBC_*} 占位；凭证只进 env + localParams（运行时由 DS 参数替换或 shell 展开）
            // 禁止把 Vault 明文写进 rawScript（与 DataX 一致）
            if (("FLINK".equals(String.valueOf(task.get("taskType"))) || "flink".equalsIgnoreCase(engine)
                    || "SPARK".equals(String.valueOf(task.get("taskType"))) || "spark".equalsIgnoreCase(engine))) {
                String sqlBody = str(taskParams.get("rawScript"));
                if (StrUtil.isNotBlank(sqlBody) && sqlBody.contains("${LH_JDBC_")) {
                    taskParams.put("lhSql", sqlBody);
                    taskParams.put("lhJdbcResolved", false);
                }
                // 有界落表：用 Vault 凭证建 lh_ods_*/lh_clean_*（仅发布侧 DDL，不写入任务脚本）
                if (IgEtlNodeTypes.SOURCE.contains(nodeType) && StrUtil.isNotBlank(landingTable)
                        && !Boolean.TRUE.equals(taskParams.get("_lhStagingColsReady"))
                        && ds != null && secret != null) {
                    Object url = secret.get("jdbcUrl") != null ? secret.get("jdbcUrl") : secret.get("url");
                    Object u = secret.get("username") != null ? secret.get("username") : secret.get("user");
                    Object p = secret.get("password");
                    List<FlinkSourceSqlCompiler.Column> cols =
                            FlinkSourceSqlCompiler.coerceColumns(taskParams.get("_lhStagingColumns"));
                    if (cols.isEmpty()) {
                        cols = FlinkSourceSqlCompiler.coerceColumns(conf.get("_lhColumnObjs"));
                    }
                    if (cols.isEmpty()) {
                        cols = FlinkSourceSqlCompiler.columnsFromConf(conf);
                    }
                    if (url != null && u != null && !cols.isEmpty()) {
                        LhStagingDdlEnsured.ensureMysqlFamily(ds, String.valueOf(url), String.valueOf(u),
                                p == null ? null : String.valueOf(p),
                                conf.getStr("database"),
                                LhStagingTables.bareTable(firstNonBlank(conf.getStr("table"), conf.getStr("src"))),
                                cols);
                        taskParams.put("_lhStagingColsReady", true);
                    }
                }
            }

            Map<String, Object> env = envMap(taskParams);
            Map<String, Object> connSafe = new LinkedHashMap<>();
            // 每次节点重建，避免重复累加 localParams
            List<Map<String, Object>> localParams = new ArrayList<>();

            if (secret != null && StrUtil.isNotBlank(vaultPath) && !icebergSink) {
                injectSecretToEnv(secret, vaultPath, env, connSafe);
                putJdbcAliases(env, secret, vaultPath, "LH_JDBC", localParams);
            }
            if (sinkSide) {
                if (secret != null && StrUtil.isNotBlank(vaultPath) && ds != null) {
                    injectSecretToEnv(secret, vaultPath, env, connSafe);
                    putJdbcAliases(env, secret, vaultPath, "LH_WRITER_JDBC", localParams);
                }
                if (readerDs != null && StrUtil.isNotBlank(readerDs.getVaultPath())) {
                    Map<String, Object> readerSecret = vaultClient.readOrEmpty(readerDs.getVaultPath());
                    if (readerSecret != null && !readerSecret.isEmpty()) {
                        putJdbcAliases(env, readerSecret, readerDs.getVaultPath(), "LH_READER_JDBC", localParams);
                        // Iceberg/Flink JDBC 源读 lh_ods_*，SQL 占位是 LH_JDBC_*
                        if (icebergSink) {
                            putJdbcAliases(env, readerSecret, readerDs.getVaultPath(), "LH_JDBC", localParams);
                        }
                        taskParams.put("lhReaderVaultPath", readerDs.getVaultPath());
                    } else {
                        notes.add(nodeKey + ": 上游读端 Vault 空 " + readerDs.getVaultPath());
                    }
                } else if (StrUtil.isNotBlank(readerDsId) && readerDs == null) {
                    notes.add(nodeKey + ": 上游 readerDsId 不存在 " + readerDsId);
                } else if (!icebergSink) {
                    notes.add(nodeKey + ": sink 缺少上游 readerDsId/_lhUpstreamDsId，未注入 LH_READER_*");
                }
            } else if (StrUtil.isNotBlank(writerDsId) && secret != null && StrUtil.isNotBlank(vaultPath)) {
                // 源节点另配写端
                putJdbcAliases(env, secret, vaultPath, "LH_READER_JDBC", localParams);
                LhDatasource wds = datasourceMapper.selectById(writerDsId);
                if (wds != null && StrUtil.isNotBlank(wds.getVaultPath())) {
                    Map<String, Object> wSecret = vaultClient.readOrEmpty(wds.getVaultPath());
                    if (wSecret != null && !wSecret.isEmpty()) {
                        putJdbcAliases(env, wSecret, wds.getVaultPath(), "LH_WRITER_JDBC", localParams);
                        taskParams.put("lhWriterVaultPath", wds.getVaultPath());
                    }
                }
            }

            taskParams.put("env", env);
            taskParams.put("localParams", sanitizeLocalParams(localParams));
            taskParams.put("connSafe", connSafe);
            injected++;
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("injected", injected);
        summary.put("missingDs", missingDs);
        summary.put("missingVault", missingVault);
        summary.put("noDsId", noDsId);
        summary.put("columnsFilled", columnsFilled);
        if (!notes.isEmpty()) {
            summary.put("notes", notes.size() > 8 ? notes.subList(0, 8) : notes);
        }
        workflow.put("vaultInjection", summary);
        return summary;
    }

    private void fillDsIntoConf(LhDatasource ds, JSONObject conf, Map<String, Object> taskParams, String role,
                               String nodeType) {
        taskParams.put("lhDsCode", ds.getDsCode());
        taskParams.put("lhDsType", ds.getType());
        taskParams.put("lhDsName", ds.getName());
        if (StrUtil.isNotBlank(ds.getEndpointHost())) {
            taskParams.put("lhHost", ds.getEndpointHost());
            conf.set("host", ds.getEndpointHost());
            conf.set("lhHost", ds.getEndpointHost());
        }
        if (StrUtil.isNotBlank(ds.getEndpointPort())) {
            taskParams.put("lhPort", ds.getEndpointPort());
            conf.set("port", ds.getEndpointPort());
            conf.set("lhPort", ds.getEndpointPort());
        }
        if (StrUtil.isNotBlank(ds.getDatabaseName())) {
            taskParams.put("lhDatabase", ds.getDatabaseName());
            conf.set("lhDatabase", ds.getDatabaseName());
            // Iceberg 汇的 database 是湖 schema，不能被数据源库名覆盖
            if (!"sink_iceberg".equals(nodeType) || StrUtil.isBlank(conf.getStr("database"))) {
                conf.set("database", ds.getDatabaseName());
            }
        }
        conf.set("lhDsType", ds.getType());
        if (StrUtil.isNotBlank(ds.getType())) {
            conf.set("dbType", ds.getType());
        }
        taskParams.put("lhDsRole", role);
    }

    /** 只填 JDBC 读连接，不覆盖 sink 的 catalog/database/table。 */
    private void fillReaderConn(LhDatasource ds, JSONObject conf, Map<String, Object> taskParams) {
        if (StrUtil.isNotBlank(ds.getEndpointHost())) {
            conf.set("host", ds.getEndpointHost());
            conf.set("lhHost", ds.getEndpointHost());
            taskParams.put("lhHost", ds.getEndpointHost());
        }
        if (StrUtil.isNotBlank(ds.getEndpointPort())) {
            conf.set("port", ds.getEndpointPort());
            conf.set("lhPort", ds.getEndpointPort());
            taskParams.put("lhPort", ds.getEndpointPort());
        }
        if (StrUtil.isNotBlank(ds.getDatabaseName())) {
            conf.set("lhDatabase", ds.getDatabaseName());
            taskParams.put("lhDatabase", ds.getDatabaseName());
        }
        if (StrUtil.isNotBlank(ds.getType())) {
            conf.set("lhReaderDsType", ds.getType());
            conf.set("dbType", ds.getType());
            conf.set("lhDsType", ds.getType());
        }
    }

    /** Vault 原文：env 审计引用 + connSafe；不进 localParams（避免与 JDBC 别名重复、direct 非法） */
    private void injectSecretToEnv(
            Map<String, Object> secret, String vaultPath,
            Map<String, Object> env, Map<String, Object> connSafe) {
        for (Map.Entry<String, Object> e : secret.entrySet()) {
            String k = e.getKey();
            if (k == null || e.getValue() == null) {
                continue;
            }
            String key = k.trim();
            if (isSecretKey(key)) {
                env.put("LH_SECRET_" + key.toUpperCase(), "vault://" + vaultPath + "#" + key);
            } else {
                String val = String.valueOf(e.getValue());
                connSafe.put(key, val);
                env.put("LH_CONN_" + key.toUpperCase(), val);
            }
        }
    }

    /**
     * 写入 LH_JDBC_* / LH_READER_JDBC_* / LH_WRITER_JDBC_*。
     * <p>运行时值进 env + localParams（DS {@code ${PROP}} / shell 展开）；脚本正文只保留占位符。</p>
     */
    private void putJdbcAliases(
            Map<String, Object> env, Map<String, Object> secret, String vaultPath, String prefix,
            List<Map<String, Object>> localParams) {
        Object url = secret.get("jdbcUrl");
        if (url == null) {
            url = secret.get("url");
        }
        if (url != null && StrUtil.isNotBlank(String.valueOf(url))) {
            String v = String.valueOf(url);
            env.put(prefix + "_URL", v);
            upsertLocal(localParams, prefix + "_URL", v);
        }
        Object u = secret.get("username") != null ? secret.get("username") : secret.get("user");
        if (u != null && StrUtil.isNotBlank(String.valueOf(u))) {
            String v = String.valueOf(u);
            env.put(prefix + "_USER", v);
            upsertLocal(localParams, prefix + "_USER", v);
        }
        if (secret.containsKey("password") && secret.get("password") != null) {
            String pw = String.valueOf(secret.get("password"));
            env.put(prefix + "_PASSWORD", pw);
            upsertLocal(localParams, prefix + "_PASSWORD", pw);
            // 审计引用只留一条，不进 localParams
            env.putIfAbsent(prefix + "_PASSWORD_REF", "vault://" + vaultPath + "#password");
        }
    }

    /** DS Property：direct 必须 IN/OUT，type 官方枚举（无 PASSWORD） */
    private static Map<String, Object> localParam(String prop, String value) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("prop", prop);
        p.put("direct", "IN");
        p.put("type", "VARCHAR");
        p.put("value", value);
        return p;
    }

    private static void upsertLocal(List<Map<String, Object>> localParams, String prop, String value) {
        if (localParams == null || StrUtil.isBlank(prop)) {
            return;
        }
        for (Map<String, Object> p : localParams) {
            if (prop.equals(String.valueOf(p.get("prop")))) {
                p.put("value", value);
                p.put("direct", "IN");
                p.put("type", "VARCHAR");
                return;
            }
        }
        localParams.add(localParam(prop, value));
    }

    /** 按 prop 去重，并强制合法 direct/type（修复历史 TRUE/FALSE、PASSWORD、null） */
    private static List<Map<String, Object>> sanitizeLocalParams(List<Map<String, Object>> raw) {
        LinkedHashMap<String, Map<String, Object>> byProp = new LinkedHashMap<>();
        if (raw != null) {
            for (Map<String, Object> p : raw) {
                if (p == null) {
                    continue;
                }
                String prop = p.get("prop") == null ? null : String.valueOf(p.get("prop")).trim();
                if (StrUtil.isBlank(prop)) {
                    continue;
                }
                Object val = p.get("value");
                byProp.put(prop, localParam(prop, val == null ? "" : String.valueOf(val)));
            }
        }
        return new ArrayList<>(byProp.values());
    }

    private String resolveOpenLineageUrl() {
        try {
            String mz = lhProperties.getMarquez() != null ? lhProperties.getMarquez().getUrl() : null;
            if (StrUtil.isNotBlank(mz)) {
                return mz.endsWith("/") ? mz + "api/v1/lineage" : mz + "/api/v1/lineage";
            }
        } catch (Exception e) {
            log.debug("resolve OpenLineage url skip: {}", e.getMessage());
        }
        return null;
    }

    private void injectPlatformEngineEnv(Map<String, Object> taskParams) {
        Map<String, Object> env = envMap(taskParams);
        if (lhProperties.getTrino() != null && StrUtil.isNotBlank(lhProperties.getTrino().getUrl())) {
            env.putIfAbsent("LH_TRINO_URL", lhProperties.getTrino().getUrl());
        }
        if (lhProperties.getQuality() != null && StrUtil.isNotBlank(lhProperties.getQuality().getGovBaseUrl())) {
            env.putIfAbsent("LH_GOV_URL", lhProperties.getQuality().getGovBaseUrl().replaceAll("/+$", ""));
        }
        if (lhProperties.getFlink() != null) {
            if (StrUtil.isNotBlank(lhProperties.getFlink().getUrl())) {
                env.putIfAbsent("LH_FLINK_URL", lhProperties.getFlink().getUrl());
            }
            if (StrUtil.isNotBlank(lhProperties.getFlink().getSqlGatewayUrl())) {
                env.putIfAbsent("LH_FLINK_SQL_GATEWAY", lhProperties.getFlink().getSqlGatewayUrl());
            }
            String flinkHome = StrUtil.blankToDefault(lhProperties.getFlink().getHome(), "/opt/flink");
            env.putIfAbsent("FLINK_HOME", flinkHome);
            env.putIfAbsent("PATH", flinkHome + "/bin:${PATH}");
        }
        if (lhProperties.getSpark() != null) {
            if (StrUtil.isNotBlank(lhProperties.getSpark().getMaster())) {
                env.putIfAbsent("LH_SPARK_MASTER", lhProperties.getSpark().getMaster());
            }
            String sparkHome = StrUtil.blankToDefault(lhProperties.getSpark().getHome(), "/opt/spark");
            env.putIfAbsent("SPARK_HOME", sparkHome);
            if (StrUtil.isNotBlank(lhProperties.getSpark().getJars())) {
                env.putIfAbsent("LH_SPARK_JARS", lhProperties.getSpark().getJars());
            }
        }
        if (lhProperties.getDatax() != null && StrUtil.isNotBlank(lhProperties.getDatax().getHome())) {
            env.putIfAbsent("LH_DATAX_HOME", lhProperties.getDatax().getHome());
        }
        taskParams.put("env", env);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> envMap(Map<String, Object> taskParams) {
        Object envObj = taskParams.get("env");
        if (envObj instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        Map<String, Object> env = new LinkedHashMap<>();
        taskParams.put("env", env);
        return env;
    }

    private static boolean isSecretKey(String key) {
        String k = key.toLowerCase();
        if (SECRET_KEYS.contains(k)) {
            return true;
        }
        return k.contains("password") || k.contains("secret") || k.contains("token") || k.endsWith("key");
    }

    private static JSONObject parseConf(String confJson) {
        if (StrUtil.isBlank(confJson)) {
            return JSONUtil.createObj();
        }
        try {
            return JSONUtil.parseObj(confJson);
        } catch (Exception e) {
            return JSONUtil.createObj();
        }
    }

    private static String firstTable(JSONObject conf) {
        Object tables = conf.get("tables");
        if (tables instanceof Iterable<?> it) {
            for (Object o : it) {
                if (o != null && StrUtil.isNotBlank(String.valueOf(o))) {
                    return String.valueOf(o).trim();
                }
            }
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

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o);
        return "null".equals(s) ? null : s;
    }
}
