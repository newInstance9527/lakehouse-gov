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
import vip.xiaonuo.lh.core.engine.FlinkIcebergSinkSqlCompiler;
import vip.xiaonuo.lh.core.engine.FlinkSourceSqlCompiler;
import vip.xiaonuo.lh.core.engine.IgEtlNodeTypes;
import vip.xiaonuo.lh.core.engine.LhStagingTables;
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.core.vault.LhVaultPaths;
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
        boolean stagingBlocked = false;
        List<String> notes = new ArrayList<>();
        List<Map<String, Object>> stagingResults = new ArrayList<>();

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
            boolean ckSink = "sink_ck".equals(nodeType);
            boolean kafkaSink = "sink_kafka".equals(nodeType);
            // Iceberg/CK/Kafka 汇：可折入 source_kafka 同作业 FLINK_STREAM
            boolean streamCapableSink = icebergSink || ckSink || kafkaSink;
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
            boolean kafkaPipeline = streamCapableSink && FlinkIcebergSinkSqlCompiler.isKafkaPipeline(conf);
            if (("source_kafka".equals(nodeType) || "source_pulsar".equals(nodeType)) && ds != null) {
                if ("source_pulsar".equals(nodeType) || "pulsar".equalsIgnoreCase(ds.getType())) {
                    fillPulsarReaderConn(ds, conf, taskParams);
                } else {
                    fillKafkaReaderConn(ds, conf, taskParams);
                }
            }
            if (StrUtil.isNotBlank(readerDsId) && (dsId == null || !readerDsId.equals(dsId))) {
                readerDs = datasourceMapper.selectById(readerDsId);
                if (readerDs != null) {
                    conf.set("lhUpstreamDsType", readerDs.getType());
                    conf.set("lhReaderDsType", readerDs.getType());
                    conf.set("_lhUpstreamDsId", readerDsId);
                    taskParams.put("lhReaderDsId", readerDsId);
                    taskParams.put("lhReaderDsType", readerDs.getType());
                    taskParams.put("lhUpstreamDsId", readerDsId);
                    if (streamCapableSink) {
                        if (kafkaPipeline || "kafka".equalsIgnoreCase(readerDs.getType())
                                || "pulsar".equalsIgnoreCase(readerDs.getType())
                                || Boolean.TRUE.equals(conf.getBool("_lhFromPulsar"))) {
                            if ("pulsar".equalsIgnoreCase(readerDs.getType())
                                    || Boolean.TRUE.equals(conf.getBool("_lhFromPulsar"))) {
                                fillPulsarReaderConn(readerDs, conf, taskParams);
                                conf.set("_lhFromPulsar", true);
                                conf.set("_lhMqKind", "pulsar");
                            } else {
                                fillKafkaReaderConn(readerDs, conf, taskParams);
                                conf.set("_lhFromKafka", true);
                            }
                            kafkaPipeline = true;
                        } else {
                            fillReaderConn(readerDs, conf, taskParams);
                        }
                    }
                }
            } else if (sinkSide && StrUtil.isNotBlank(readerDsId) && readerDsId.equals(dsId)) {
                if (kafkaSink || kafkaPipeline) {
                    // Kafka 扇出：源/目标可同集群 ds
                    readerDs = ds;
                } else {
                    // 汇点 reader 不能与写端同 ds（否则 mysqlreader 会吃到 postgres URL）
                    notes.add(nodeKey + ": sink readerDsId 与写端 dsId 相同，忽略并等待上游解析");
                    readerDsId = null;
                }
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

            // 列元数据：源节点按本 ds；汇 DataX 按读端（Kafka JSON 用 schemaTemplate，跳过 JDBC 列拉取）
            String colDsId = IgEtlNodeTypes.SOURCE.contains(nodeType) ? dsId : readerDsId;
            String colTable = IgEtlNodeTypes.SOURCE.contains(nodeType)
                    ? firstNonBlank(conf.getStr("table"), conf.getStr("src"), firstTable(conf))
                    : firstNonBlank(upstreamTable, conf.getStr("src"), conf.getStr("table"));
            boolean skipJdbcColumns = kafkaPipeline || "source_kafka".equals(nodeType)
                    || "source_pulsar".equals(nodeType)
                    || (colTable != null && (colTable.startsWith("_lh_kfk_") || colTable.startsWith("_lh_psr_")))
                    || "kafka".equalsIgnoreCase(StrUtil.blankToDefault(
                            conf.getStr("lhReaderDsType"), conf.getStr("lhDsType")))
                    || "pulsar".equalsIgnoreCase(StrUtil.blankToDefault(
                            conf.getStr("lhReaderDsType"), conf.getStr("lhDsType")));
            if (!skipJdbcColumns && StrUtil.isNotBlank(colDsId) && StrUtil.isNotBlank(colTable)) {
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
                    // 源→清洗→入库：预建或预检 lh_ods_* / lh_clean_*
                    if (IgEtlNodeTypes.SOURCE.contains(nodeType) && StrUtil.isNotBlank(landingTable)) {
                        String jdbcUrl = firstNonBlank(conf.getStr("lhJdbcUrl"), conf.getStr("jdbcUrl"));
                        String u = firstNonBlank(conf.getStr("lhJdbcUser"), conf.getStr("user"));
                        String pw = firstNonBlank(conf.getStr("lhJdbcPassword"), conf.getStr("password"));
                        if (StrUtil.isNotBlank(jdbcUrl) && StrUtil.isNotBlank(u)) {
                            if (!applyStagingEnsure(ds, jdbcUrl, u, pw, conf, cols, nodeKey, notes,
                                    taskParams, stagingResults)) {
                                stagingBlocked = true;
                            }
                        }
                    }
                }
            }

            boolean datax = "datax".equalsIgnoreCase(engine) || taskParams.containsKey("lhDataxJob")
                    || "DATAX".equals(String.valueOf(task.get("taskType")));
            boolean flinkOrSpark = "flink".equalsIgnoreCase(engine) || "spark".equalsIgnoreCase(engine)
                    || "FLINK".equals(String.valueOf(task.get("taskType")))
                    || "FLINK_STREAM".equals(String.valueOf(task.get("taskType")))
                    || "SPARK".equals(String.valueOf(task.get("taskType")));

            if (datax) {
                taskParams.put("lhDataxJob", DataxJobBuilder.buildJob(node, conf));
                taskParams.put("rawScript", DataxJobBuilder.buildShell(node, conf));
            }
            boolean kafkaStreamPlaceholder = "source_kafka".equals(nodeType)
                    || "source_pulsar".equals(nodeType)
                    || (("clean".equals(nodeType) || "mapping".equals(nodeType))
                    && (Boolean.TRUE.equals(conf.getBool("_lhOnKafkaPipeline"))
                    || "true".equalsIgnoreCase(StrUtil.blankToDefault(conf.getStr("_lhOnKafkaPipeline"), ""))));
            // source_kafka|pulsar / 流式 clean·mapping 占位 SHELL，勿用 Flink/Spark SQL 覆盖
            if (flinkOrSpark && !kafkaStreamPlaceholder
                    && (IgEtlNodeTypes.SOURCE.contains(nodeType)
                    || "clean".equals(nodeType)
                    || "transform".equals(nodeType)
                    || "mapping".equals(nodeType)
                    || "sink_rdb".equals(nodeType) || "sink_ck".equals(nodeType)
                    || "sink_kafka".equals(nodeType)
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
                    // Kafka→Iceberg 保持 FLINK_STREAM；批入湖用 FLINK
                    String flinkType = kafkaPipeline || FlinkIcebergSinkSqlCompiler.isKafkaPipeline(conf)
                            ? "FLINK_STREAM" : "FLINK";
                    task.put("taskType", flinkType);
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

            // Flink/Spark/DataX：脚本/JSON 保留 ${LH_JDBC_*}/${LH_KAFKA_*} 占位；凭证只进 env + localParams
            // 禁止把 Vault 明文写进 rawScript（与 DataX 一致）
            if (("FLINK".equals(String.valueOf(task.get("taskType")))
                    || "FLINK_STREAM".equals(String.valueOf(task.get("taskType")))
                    || "flink".equalsIgnoreCase(engine)
                    || "SPARK".equals(String.valueOf(task.get("taskType"))) || "spark".equalsIgnoreCase(engine))) {
                String sqlBody = str(taskParams.get("rawScript"));
                if (StrUtil.isNotBlank(sqlBody) && (sqlBody.contains("${LH_JDBC_")
                        || sqlBody.contains("${LH_KAFKA_")
                        || sqlBody.contains("${LH_PULSAR_")
                        || sqlBody.contains("${LH_CK_"))) {
                    taskParams.put("lhSql", sqlBody);
                    taskParams.put("lhJdbcResolved", false);
                }
                // 有界落表：用 Vault 凭证建 lh_ods_*/lh_clean_*（仅发布侧 DDL，不写入任务脚本）
                if (!kafkaPipeline && !"source_kafka".equals(nodeType) && !"source_pulsar".equals(nodeType)
                        && IgEtlNodeTypes.SOURCE.contains(nodeType) && StrUtil.isNotBlank(landingTable)
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
                        if (!applyStagingEnsure(ds, String.valueOf(url), String.valueOf(u),
                                p == null ? null : String.valueOf(p), conf, cols, nodeKey, notes,
                                taskParams, stagingResults)) {
                            stagingBlocked = true;
                        }
                    }
                }
            }

            Map<String, Object> env = envMap(taskParams);
            Map<String, Object> connSafe = new LinkedHashMap<>();
            // 每次节点重建，避免重复累加 localParams
            List<Map<String, Object>> localParams = new ArrayList<>();

            String dsType = StrUtil.blankToDefault(ds == null ? null : ds.getType(), "").toLowerCase();
            if (secret != null && StrUtil.isNotBlank(vaultPath) && !icebergSink) {
                injectSecretToEnv(secret, vaultPath, env, connSafe);
                if ("source_pulsar".equals(nodeType) || "pulsar".equals(dsType)) {
                    putPulsarAliases(env, secret, vaultPath, conf, localParams);
                } else if ("source_kafka".equals(nodeType) || "kafka".equals(dsType)) {
                    putKafkaAliases(env, secret, vaultPath, conf, localParams);
                } else if ("source_rabbitmq".equals(nodeType) || "rabbitmq".equals(dsType)) {
                    putRabbitAliases(env, secret, vaultPath, conf, localParams);
                } else if ("source_mongo".equals(nodeType) || "mongodb".equals(dsType)) {
                    putMongoAliases(env, secret, vaultPath, conf, localParams);
                } else if ("source_hbase".equals(nodeType) || "hbase".equals(dsType)) {
                    putHbaseAliases(env, secret, vaultPath, conf, localParams);
                } else if (isObjectStore(dsType) || "hdfs".equals(dsType)) {
                    putOssAliases(env, secret, vaultPath, conf, localParams);
                    putHdfsAliases(env, secret, vaultPath, conf, localParams);
                } else if ("source_file".equals(nodeType) || "sink_ftp".equals(nodeType)
                        || isFtpFamily(dsType)) {
                    putFtpAliases(env, secret, vaultPath, conf, localParams);
                } else if ("source_api".equals(nodeType) || "http_api".equals(dsType)) {
                    putHttpAliases(env, secret, vaultPath, conf, localParams);
                } else {
                    putJdbcAliases(env, secret, vaultPath, "LH_JDBC", localParams);
                }
            }
            if (sinkSide) {
                if (secret != null && StrUtil.isNotBlank(vaultPath) && ds != null) {
                    injectSecretToEnv(secret, vaultPath, env, connSafe);
                    String wType = StrUtil.blankToDefault(ds.getType(), "").toLowerCase();
                    if (kafkaSink || "kafka".equals(wType)) {
                        putKafkaAliases(env, secret, vaultPath, conf, localParams);
                    } else if (ckSink || isClickHouseType(ds.getType())) {
                        putCkAliases(env, secret, vaultPath, conf, localParams);
                        putJdbcAliases(env, secret, vaultPath, "LH_WRITER_JDBC", localParams);
                    } else if ("sink_ftp".equals(nodeType) || isFtpFamily(wType)) {
                        putFtpAliases(env, secret, vaultPath, conf, localParams);
                    } else if ("sink_object".equals(nodeType) || isObjectStore(wType) || "hdfs".equals(wType)) {
                        putOssAliases(env, secret, vaultPath, conf, localParams);
                        putHdfsAliases(env, secret, vaultPath, conf, localParams);
                    } else if ("sink_search".equals(nodeType) || wType.contains("elastic")) {
                        putEsAliases(env, secret, vaultPath, conf, localParams);
                    } else {
                        putJdbcAliases(env, secret, vaultPath, "LH_WRITER_JDBC", localParams);
                        if ("doris".equals(wType) || "starrocks".equals(wType)) {
                            putFeHttpAliases(env, secret, vaultPath, conf, localParams, wType);
                        }
                    }
                }
                // sink_ck 无写端 ds 时回落平台 ClickHouse Vault
                if (ckSink && (secret == null || StrUtil.isBlank(vaultPath))) {
                    injectPlatformClickHouse(env, conf, localParams);
                } else if (ckSink && secret != null) {
                    // 已有写端 secret：再补 LH_CK_*（若 putCkAliases 未覆盖完整 URL）
                    if (!env.containsKey("LH_CK_USER")) {
                        putCkAliases(env, secret, vaultPath, conf, localParams);
                    }
                }
                if (readerDs != null && StrUtil.isNotBlank(readerDs.getVaultPath())) {
                    Map<String, Object> readerSecret = vaultClient.readOrEmpty(readerDs.getVaultPath());
                    if (readerSecret != null && !readerSecret.isEmpty()) {
                        if (kafkaPipeline || "kafka".equalsIgnoreCase(readerDs.getType())
                                || "pulsar".equalsIgnoreCase(readerDs.getType())) {
                            if ("pulsar".equalsIgnoreCase(readerDs.getType())
                                    || Boolean.TRUE.equals(conf.getBool("_lhFromPulsar"))) {
                                putPulsarAliases(env, readerSecret, readerDs.getVaultPath(), conf, localParams);
                                fillPulsarFromSecret(conf, readerSecret, readerDs);
                            } else {
                                putKafkaAliases(env, readerSecret, readerDs.getVaultPath(), conf, localParams);
                                fillKafkaBootstrapFromSecret(conf, readerSecret, readerDs);
                            }
                            if (streamCapableSink) {
                                String rebuilt = DsTaskScriptBuilder.resolveSql(node, conf);
                                taskParams.put("lhSql", rebuilt);
                                String flinkSql = rebuilt.endsWith(";") ? rebuilt : rebuilt + ";";
                                taskParams.put("rawScript", flinkSql);
                                task.put("taskType", "FLINK_STREAM");
                            }
                        } else {
                            putJdbcAliases(env, readerSecret, readerDs.getVaultPath(), "LH_READER_JDBC", localParams);
                            // Iceberg/CK/Flink JDBC 源读 lh_ods_*，SQL 占位是 LH_JDBC_*
                            if (streamCapableSink) {
                                putJdbcAliases(env, readerSecret, readerDs.getVaultPath(), "LH_JDBC", localParams);
                            }
                        }
                        taskParams.put("lhReaderVaultPath", readerDs.getVaultPath());
                    } else {
                        notes.add(nodeKey + ": 上游读端 Vault 空 " + readerDs.getVaultPath());
                    }
                } else if (StrUtil.isNotBlank(readerDsId) && readerDs == null) {
                    notes.add(nodeKey + ": 上游 readerDsId 不存在 " + readerDsId);
                } else if (!icebergSink && !kafkaPipeline) {
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
        summary.put("stagingBlocked", stagingBlocked);
        if (!stagingResults.isEmpty()) {
            summary.put("staging", stagingResults.size() > 4 ? stagingResults.subList(0, 4) : stagingResults);
        }
        if (!notes.isEmpty()) {
            summary.put("notes", notes.size() > 8 ? notes.subList(0, 8) : notes);
        }
        workflow.put("vaultInjection", summary);
        return summary;
    }

    /**
     * @return false 表示应阻断发布（预建关闭且表不存在，或自动建表失败）
     */
    private boolean applyStagingEnsure(
            LhDatasource ds, String jdbcUrl, String user, String password, JSONObject conf,
            List<FlinkSourceSqlCompiler.Column> cols, String nodeKey, List<String> notes,
            Map<String, Object> taskParams, List<Map<String, Object>> stagingResults) {
        if (Boolean.TRUE.equals(taskParams.get("_lhStagingColsReady"))) {
            return true;
        }
        String database = firstNonBlank(conf.getStr("database"), conf.getStr("lhDatabase"), ds.getDatabaseName());
        String bare = LhStagingTables.bareTable(firstNonBlank(conf.getStr("table"), conf.getStr("src")));
        LhStagingDdlEnsured.ResolvedTables rt = LhStagingDdlEnsured.resolveTables(database, bare, conf, null);
        boolean autoCreate = LhStagingDdlEnsured.isAutoCreate(conf);
        LhStagingDdlEnsured.EnsureResult er = LhStagingDdlEnsured.ensureMysqlFamilyDetailed(
                ds, jdbcUrl, user, password, database, rt.landingBare, rt.cleanBare, cols, autoCreate);
        Map<String, Object> one = LhStagingDdlEnsured.toMap(er);
        one.put("nodeKey", nodeKey);
        stagingResults.add(one);
        conf.set("_lhLandingTable", rt.landingQualified);
        conf.set("_lhCleanTable", rt.cleanQualified);
        taskParams.put("lhLandingTable", rt.landingQualified);
        taskParams.put("lhCleanTable", rt.cleanQualified);
        if (er.ok) {
            taskParams.put("_lhStagingColsReady", true);
            return true;
        }
        notes.add(nodeKey + ": " + er.message);
        if (StrUtil.isNotBlank(er.ddlHint)) {
            notes.add(nodeKey + ": ddlHint → 见 sideEffects.vault.staging");
            taskParams.put("_lhStagingDdlHint", er.ddlHint);
        }
        // 自动创建失败仍 soft（兼容旧行为）；关闭自动创建且缺表则阻断
        return autoCreate;
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

    /** Kafka 读端：bootstrap = host:port（或已带端口的 host）。 */
    private void fillKafkaReaderConn(LhDatasource ds, JSONObject conf, Map<String, Object> taskParams) {
        if (ds == null || conf == null) {
            return;
        }
        if (StrUtil.isNotBlank(ds.getType())) {
            conf.set("lhReaderDsType", ds.getType());
            conf.set("dbType", ds.getType());
            conf.set("lhDsType", ds.getType());
            taskParams.put("lhReaderDsType", ds.getType());
        }
        String host = ds.getEndpointHost();
        String port = ds.getEndpointPort();
        if (StrUtil.isNotBlank(host)) {
            conf.set("host", host);
            conf.set("lhHost", host);
            taskParams.put("lhHost", host);
            String bootstrap = host.contains(":") || StrUtil.isBlank(port) ? host : host + ":" + port;
            if (StrUtil.isBlank(conf.getStr("bootstrap"))) {
                conf.set("bootstrap", bootstrap);
            }
            conf.set("lhBootstrap", bootstrap);
            taskParams.put("lhBootstrap", bootstrap);
        }
        if (StrUtil.isNotBlank(port)) {
            conf.set("port", port);
            conf.set("lhPort", port);
            taskParams.put("lhPort", port);
        }
    }

    private void fillPulsarReaderConn(LhDatasource ds, JSONObject conf, Map<String, Object> taskParams) {
        if (ds == null || conf == null) {
            return;
        }
        if (StrUtil.isNotBlank(ds.getType())) {
            conf.set("lhReaderDsType", ds.getType());
            conf.set("dbType", ds.getType());
            conf.set("lhDsType", ds.getType());
            taskParams.put("lhReaderDsType", ds.getType());
        }
        conf.set("_lhFromPulsar", true);
        conf.set("_lhMqKind", "pulsar");
        String host = ds.getEndpointHost();
        String port = ds.getEndpointPort();
        if (StrUtil.isNotBlank(host)) {
            conf.set("host", host);
            conf.set("lhHost", host);
            String svc = host.startsWith("pulsar://") || host.startsWith("pulsar+ssl://")
                    ? host
                    : "pulsar://" + host + (StrUtil.isNotBlank(port) && !host.contains(":") ? ":" + port : "");
            if (StrUtil.isBlank(conf.getStr("serviceUrl"))) {
                conf.set("serviceUrl", svc);
            }
            conf.set("lhBootstrap", svc);
            taskParams.put("lhBootstrap", svc);
        }
        if (StrUtil.isNotBlank(port)) {
            conf.set("port", port);
            conf.set("lhPort", port);
        }
    }

    private void fillPulsarFromSecret(JSONObject conf, Map<String, Object> secret, LhDatasource ds) {
        if (conf == null) {
            return;
        }
        Object u = secret == null ? null : firstNonNull(
                secret.get("serviceUrl"), secret.get("brokerServiceUrl"), secret.get("brokers"));
        if (u != null && StrUtil.isNotBlank(String.valueOf(u))) {
            conf.set("serviceUrl", String.valueOf(u).trim());
            return;
        }
        if (ds != null) {
            fillPulsarReaderConn(ds, conf, new LinkedHashMap<>());
        }
    }

    private void putMongoAliases(
            Map<String, Object> env, Map<String, Object> secret, String vaultPath, JSONObject conf,
            List<Map<String, Object>> localParams) {
        Object u = secret.get("username") != null ? secret.get("username") : secret.get("user");
        if (u != null && StrUtil.isNotBlank(String.valueOf(u))) {
            String v = String.valueOf(u);
            env.put("LH_MONGO_USER", v);
            upsertLocal(localParams, "LH_MONGO_USER", v);
        }
        if (secret.containsKey("password") && secret.get("password") != null) {
            String pw = String.valueOf(secret.get("password"));
            env.put("LH_MONGO_PASSWORD", pw);
            upsertLocal(localParams, "LH_MONGO_PASSWORD", pw);
            env.putIfAbsent("LH_MONGO_PASSWORD_REF", "vault://" + vaultPath + "#password");
        }
        Object host = firstNonNull(secret.get("host"), secret.get("hostname"));
        if (host != null && conf != null && StrUtil.isBlank(conf.getStr("host"))) {
            conf.set("host", String.valueOf(host).trim());
        }
    }

    private void putHbaseAliases(
            Map<String, Object> env, Map<String, Object> secret, String vaultPath, JSONObject conf,
            List<Map<String, Object>> localParams) {
        Object zk = firstNonNull(secret.get("zkQuorum"), secret.get("zookeeper"), secret.get("host"));
        if (zk != null && StrUtil.isNotBlank(String.valueOf(zk))) {
            String v = String.valueOf(zk).trim();
            env.put("LH_HBASE_ZK", v);
            upsertLocal(localParams, "LH_HBASE_ZK", v);
            if (conf != null && StrUtil.isBlank(conf.getStr("zkQuorum"))) {
                conf.set("zkQuorum", v);
            }
        }
        if (secret.containsKey("password") && secret.get("password") != null) {
            env.putIfAbsent("LH_HBASE_PASSWORD_REF", "vault://" + vaultPath + "#password");
        }
    }

    private void putPulsarAliases(
            Map<String, Object> env, Map<String, Object> secret, String vaultPath, JSONObject conf,
            List<Map<String, Object>> localParams) {
        Object u = firstNonNull(secret.get("serviceUrl"), secret.get("brokerServiceUrl"), secret.get("brokers"));
        if (u == null || StrUtil.isBlank(String.valueOf(u))) {
            u = conf == null ? null : firstNonBlank(conf.getStr("serviceUrl"), conf.getStr("lhBootstrap"));
        }
        if (u != null && StrUtil.isNotBlank(String.valueOf(u))) {
            String v = String.valueOf(u).trim();
            env.put("LH_PULSAR_SERVICE_URL", v);
            upsertLocal(localParams, "LH_PULSAR_SERVICE_URL", v);
            if (conf != null && StrUtil.isBlank(conf.getStr("serviceUrl"))) {
                conf.set("serviceUrl", v);
            }
        }
        Object admin = firstNonNull(secret.get("adminUrl"), secret.get("webServiceUrl"));
        if (admin != null && StrUtil.isNotBlank(String.valueOf(admin))) {
            String v = String.valueOf(admin).trim();
            env.put("LH_PULSAR_ADMIN_URL", v);
            upsertLocal(localParams, "LH_PULSAR_ADMIN_URL", v);
            if (conf != null && StrUtil.isBlank(conf.getStr("adminUrl"))) {
                conf.set("adminUrl", v);
            }
        }
        Object user = secret.get("username") != null ? secret.get("username") : secret.get("user");
        if (user != null && StrUtil.isNotBlank(String.valueOf(user))) {
            String v = String.valueOf(user);
            env.put("LH_PULSAR_USER", v);
            upsertLocal(localParams, "LH_PULSAR_USER", v);
        }
        if (secret.containsKey("password") && secret.get("password") != null) {
            String pw = String.valueOf(secret.get("password"));
            env.put("LH_PULSAR_PASSWORD", pw);
            upsertLocal(localParams, "LH_PULSAR_PASSWORD", pw);
            env.putIfAbsent("LH_PULSAR_PASSWORD_REF", "vault://" + vaultPath + "#password");
        }
    }

    private void fillKafkaBootstrapFromSecret(JSONObject conf, Map<String, Object> secret, LhDatasource ds) {
        if (conf == null) {
            return;
        }
        Object b = secret == null ? null : firstNonNull(
                secret.get("bootstrap"), secret.get("bootstrapServers"), secret.get("brokers"));
        if (b != null && StrUtil.isNotBlank(String.valueOf(b))) {
            conf.set("bootstrap", String.valueOf(b).trim());
            return;
        }
        if (ds != null) {
            fillKafkaReaderConn(ds, conf, new LinkedHashMap<>());
        }
    }

    /** LH_KAFKA_BOOTSTRAP / 可选 SASL 用户口令（脚本占位 + env）。 */
    private void putKafkaAliases(
            Map<String, Object> env, Map<String, Object> secret, String vaultPath, JSONObject conf,
            List<Map<String, Object>> localParams) {
        Object b = firstNonNull(secret.get("bootstrap"), secret.get("bootstrapServers"), secret.get("brokers"));
        if (b == null || StrUtil.isBlank(String.valueOf(b))) {
            b = conf == null ? null : firstNonBlank(conf.getStr("bootstrap"), conf.getStr("lhBootstrap"));
        }
        if (b != null && StrUtil.isNotBlank(String.valueOf(b))) {
            String v = String.valueOf(b).trim();
            env.put("LH_KAFKA_BOOTSTRAP", v);
            upsertLocal(localParams, "LH_KAFKA_BOOTSTRAP", v);
            if (conf != null && StrUtil.isBlank(conf.getStr("bootstrap"))) {
                conf.set("bootstrap", v);
            }
        }
        Object u = secret.get("username") != null ? secret.get("username") : secret.get("user");
        if (u != null && StrUtil.isNotBlank(String.valueOf(u))) {
            String v = String.valueOf(u);
            env.put("LH_KAFKA_USER", v);
            upsertLocal(localParams, "LH_KAFKA_USER", v);
        }
        if (secret.containsKey("password") && secret.get("password") != null) {
            String pw = String.valueOf(secret.get("password"));
            env.put("LH_KAFKA_PASSWORD", pw);
            upsertLocal(localParams, "LH_KAFKA_PASSWORD", pw);
            env.putIfAbsent("LH_KAFKA_PASSWORD_REF", "vault://" + vaultPath + "#password");
        }
        // 有凭证则标记汇点 SQL 生成 SASL 占位（勿把明文写回 conf）
        if (conf != null && (env.containsKey("LH_KAFKA_USER") || env.containsKey("LH_KAFKA_PASSWORD"))) {
            conf.set("_lhKafkaSasl", true);
            if (StrUtil.isBlank(conf.getStr("security"))
                    || "PLAINTEXT".equalsIgnoreCase(conf.getStr("security"))) {
                conf.set("security", "SASL_PLAINTEXT");
            }
        }
    }

    /** ClickHouse 写端：{@code LH_CK_JDBC_URL}/{@code LH_CK_USER}/{@code LH_CK_PASSWORD}。 */
    private void putCkAliases(
            Map<String, Object> env, Map<String, Object> secret, String vaultPath, JSONObject conf,
            List<Map<String, Object>> localParams) {
        if (secret == null) {
            return;
        }
        Object url = firstNonNull(secret.get("jdbcUrl"), secret.get("url"));
        if (url == null || StrUtil.isBlank(String.valueOf(url))) {
            Object host = firstNonNull(secret.get("host"), secret.get("hostname"));
            Object port = firstNonNull(secret.get("port"), secret.get("httpPort"), "8123");
            String db = conf == null ? "default"
                    : StrUtil.blankToDefault(firstNonBlank(conf.getStr("database"), conf.getStr("schema")), "default");
            if (host != null && StrUtil.isNotBlank(String.valueOf(host))) {
                url = "jdbc:clickhouse://" + String.valueOf(host).trim() + ":"
                        + String.valueOf(port).trim() + "/" + db;
            }
        }
        if (url != null && StrUtil.isNotBlank(String.valueOf(url))) {
            String v = String.valueOf(url).trim();
            env.put("LH_CK_JDBC_URL", v);
            upsertLocal(localParams, "LH_CK_JDBC_URL", v);
            if (conf != null && StrUtil.isBlank(conf.getStr("jdbcUrl"))) {
                conf.set("jdbcUrl", v);
            }
        }
        Object u = secret.get("username") != null ? secret.get("username") : secret.get("user");
        if (u != null && StrUtil.isNotBlank(String.valueOf(u))) {
            String v = String.valueOf(u);
            env.put("LH_CK_USER", v);
            upsertLocal(localParams, "LH_CK_USER", v);
        }
        if (secret.containsKey("password") && secret.get("password") != null) {
            String pw = String.valueOf(secret.get("password"));
            env.put("LH_CK_PASSWORD", pw);
            upsertLocal(localParams, "LH_CK_PASSWORD", pw);
            if (StrUtil.isNotBlank(vaultPath)) {
                env.putIfAbsent("LH_CK_PASSWORD_REF", "vault://" + vaultPath + "#password");
            }
        }
    }

    /** 无写端 ds 时：平台 ClickHouse（{@code lh.clickhouse.*} / Vault path）。 */
    private void injectPlatformClickHouse(
            Map<String, Object> env, JSONObject conf, List<Map<String, Object>> localParams) {
        LhProperties.Clickhouse ck = lhProperties == null ? null : lhProperties.getClickhouse();
        String path = ck != null && StrUtil.isNotBlank(ck.getVaultPath())
                ? ck.getVaultPath() : LhVaultPaths.CLICKHOUSE;
        Map<String, Object> secret = vaultClient.readOrEmpty(path);
        if (secret == null || secret.isEmpty()) {
            // application 明文回落（仅联调）
            if (ck != null) {
                Map<String, Object> fallback = new LinkedHashMap<>();
                if (StrUtil.isNotBlank(ck.getUrl())) {
                    fallback.put("jdbcUrl", ck.getUrl());
                }
                if (StrUtil.isNotBlank(ck.getUser())) {
                    fallback.put("user", ck.getUser());
                }
                if (StrUtil.isNotBlank(ck.getPassword())) {
                    fallback.put("password", ck.getPassword());
                }
                if (!fallback.isEmpty()) {
                    putCkAliases(env, fallback, path, conf, localParams);
                }
            }
            return;
        }
        putCkAliases(env, secret, path, conf, localParams);
    }

    private static boolean isClickHouseType(String type) {
        if (StrUtil.isBlank(type)) {
            return false;
        }
        String t = type.toLowerCase(java.util.Locale.ROOT);
        return t.contains("clickhouse") || "ck".equals(t);
    }

    private static boolean isFtpFamily(String type) {
        if (StrUtil.isBlank(type)) {
            return false;
        }
        String t = type.toLowerCase(java.util.Locale.ROOT);
        return t.contains("ftp") || t.contains("sftp") || "file".equals(t);
    }

    private static boolean isObjectStore(String type) {
        if (StrUtil.isBlank(type)) {
            return false;
        }
        String t = type.toLowerCase(java.util.Locale.ROOT);
        return t.contains("s3") || t.contains("minio") || t.contains("oss");
    }

    private void putRabbitAliases(
            Map<String, Object> env, Map<String, Object> secret, String vaultPath, JSONObject conf,
            List<Map<String, Object>> localParams) {
        Object host = firstNonNull(secret.get("host"), secret.get("hostname"), secret.get("endpoint"));
        if (host != null && StrUtil.isNotBlank(String.valueOf(host))) {
            String v = String.valueOf(host).trim();
            env.put("LH_RABBIT_HOST", v);
            upsertLocal(localParams, "LH_RABBIT_HOST", v);
            if (conf != null && StrUtil.isBlank(conf.getStr("host"))) {
                conf.set("host", v);
            }
        }
        Object port = firstNonNull(secret.get("port"), secret.get("amqpPort"), "5672");
        if (port != null) {
            String v = String.valueOf(port).trim();
            env.put("LH_RABBIT_PORT", v);
            upsertLocal(localParams, "LH_RABBIT_PORT", v);
        }
        Object vh = firstNonNull(secret.get("vhost"), secret.get("virtualHost"));
        if (vh != null && StrUtil.isNotBlank(String.valueOf(vh))) {
            String v = String.valueOf(vh).trim();
            env.put("LH_RABBIT_VHOST", v);
            upsertLocal(localParams, "LH_RABBIT_VHOST", v);
            if (conf != null && StrUtil.isBlank(conf.getStr("vhost"))) {
                conf.set("vhost", v);
            }
        }
        Object u = secret.get("username") != null ? secret.get("username") : secret.get("user");
        if (u != null && StrUtil.isNotBlank(String.valueOf(u))) {
            String v = String.valueOf(u);
            env.put("LH_RABBIT_USER", v);
            upsertLocal(localParams, "LH_RABBIT_USER", v);
        }
        if (secret.containsKey("password") && secret.get("password") != null) {
            String pw = String.valueOf(secret.get("password"));
            env.put("LH_RABBIT_PASSWORD", pw);
            upsertLocal(localParams, "LH_RABBIT_PASSWORD", pw);
            env.putIfAbsent("LH_RABBIT_PASSWORD_REF", "vault://" + vaultPath + "#password");
        }
    }

    private void putFtpAliases(
            Map<String, Object> env, Map<String, Object> secret, String vaultPath, JSONObject conf,
            List<Map<String, Object>> localParams) {
        Object host = firstNonNull(secret.get("host"), secret.get("hostname"), secret.get("endpoint"));
        if (host != null && StrUtil.isNotBlank(String.valueOf(host))) {
            String v = String.valueOf(host).trim();
            env.put("LH_FTP_HOST", v);
            upsertLocal(localParams, "LH_FTP_HOST", v);
            if (conf != null && StrUtil.isBlank(conf.getStr("host"))) {
                conf.set("host", v);
            }
        }
        Object port = firstNonNull(secret.get("port"), "22");
        if (port != null) {
            String v = String.valueOf(port).trim();
            env.put("LH_FTP_PORT", v);
            upsertLocal(localParams, "LH_FTP_PORT", v);
        }
        Object u = secret.get("username") != null ? secret.get("username") : secret.get("user");
        if (u != null && StrUtil.isNotBlank(String.valueOf(u))) {
            String v = String.valueOf(u);
            env.put("LH_FTP_USER", v);
            upsertLocal(localParams, "LH_FTP_USER", v);
        }
        if (secret.containsKey("password") && secret.get("password") != null) {
            String pw = String.valueOf(secret.get("password"));
            env.put("LH_FTP_PASSWORD", pw);
            upsertLocal(localParams, "LH_FTP_PASSWORD", pw);
            env.putIfAbsent("LH_FTP_PASSWORD_REF", "vault://" + vaultPath + "#password");
        }
        Object proto = firstNonNull(secret.get("protocol"), conf == null ? null : conf.getStr("protocol"));
        if (proto != null && StrUtil.isNotBlank(String.valueOf(proto))) {
            env.put("LH_FTP_PROTOCOL", String.valueOf(proto).trim().toLowerCase(java.util.Locale.ROOT));
        }
    }

    private void putOssAliases(
            Map<String, Object> env, Map<String, Object> secret, String vaultPath, JSONObject conf,
            List<Map<String, Object>> localParams) {
        Object ep = firstNonNull(secret.get("endpoint"), secret.get("endPoint"), secret.get("host"));
        if (ep != null && StrUtil.isNotBlank(String.valueOf(ep))) {
            String v = String.valueOf(ep).trim();
            env.put("LH_OSS_ENDPOINT", v);
            upsertLocal(localParams, "LH_OSS_ENDPOINT", v);
            if (conf != null && StrUtil.isBlank(conf.getStr("endpoint"))) {
                conf.set("endpoint", v);
            }
        }
        Object ak = firstNonNull(secret.get("accessKey"), secret.get("accessId"), secret.get("accessKeyId"),
                secret.get("user"), secret.get("username"));
        if (ak != null && StrUtil.isNotBlank(String.valueOf(ak))) {
            String v = String.valueOf(ak);
            env.put("LH_OSS_ACCESS_ID", v);
            upsertLocal(localParams, "LH_OSS_ACCESS_ID", v);
        }
        Object sk = firstNonNull(secret.get("secretKey"), secret.get("accessKeySecret"), secret.get("password"),
                secret.get("secret"));
        if (sk != null && StrUtil.isNotBlank(String.valueOf(sk))) {
            String v = String.valueOf(sk);
            env.put("LH_OSS_ACCESS_KEY", v);
            upsertLocal(localParams, "LH_OSS_ACCESS_KEY", v);
            env.putIfAbsent("LH_OSS_ACCESS_KEY_REF", "vault://" + vaultPath + "#secretKey");
        }
        Object bucket = firstNonNull(secret.get("bucket"), conf == null ? null : conf.getStr("bucket"));
        if (bucket != null && StrUtil.isNotBlank(String.valueOf(bucket))) {
            String v = String.valueOf(bucket).trim();
            env.put("LH_OSS_BUCKET", v);
            upsertLocal(localParams, "LH_OSS_BUCKET", v);
        }
    }

    private void putHdfsAliases(
            Map<String, Object> env, Map<String, Object> secret, String vaultPath, JSONObject conf,
            List<Map<String, Object>> localParams) {
        Object fs = firstNonNull(secret.get("defaultFS"), secret.get("fsDefault"), secret.get("namenode"),
                secret.get("endpoint"), secret.get("host"));
        if (fs != null && StrUtil.isNotBlank(String.valueOf(fs))) {
            String v = String.valueOf(fs).trim();
            if (!v.startsWith("hdfs://") && !v.contains("://")) {
                Object port = firstNonNull(secret.get("port"), "8020");
                v = "hdfs://" + v + (v.contains(":") ? "" : ":" + port);
            }
            env.put("LH_HDFS_DEFAULT_FS", v);
            upsertLocal(localParams, "LH_HDFS_DEFAULT_FS", v);
            if (conf != null && StrUtil.isBlank(conf.getStr("defaultFS"))) {
                conf.set("defaultFS", v);
            }
        }
        if (secret.containsKey("password") && secret.get("password") != null) {
            env.putIfAbsent("LH_HDFS_PASSWORD_REF", "vault://" + vaultPath + "#password");
        }
    }

    private void putHttpAliases(
            Map<String, Object> env, Map<String, Object> secret, String vaultPath, JSONObject conf,
            List<Map<String, Object>> localParams) {
        Object url = firstNonNull(secret.get("baseURL"), secret.get("baseUrl"), secret.get("url"),
                secret.get("endpoint"), secret.get("host"));
        if (url != null && StrUtil.isNotBlank(String.valueOf(url))) {
            String v = String.valueOf(url).trim();
            env.put("LH_HTTP_URL", v);
            upsertLocal(localParams, "LH_HTTP_URL", v);
            if (conf != null && StrUtil.isBlank(conf.getStr("baseUrl"))) {
                conf.set("baseUrl", v);
            }
        }
        Object token = firstNonNull(secret.get("token"), secret.get("apiKey"), secret.get("password"));
        if (token != null && StrUtil.isNotBlank(String.valueOf(token))) {
            env.put("LH_HTTP_TOKEN", String.valueOf(token));
            env.putIfAbsent("LH_HTTP_TOKEN_REF", "vault://" + vaultPath + "#token");
        }
    }

    private void putEsAliases(
            Map<String, Object> env, Map<String, Object> secret, String vaultPath, JSONObject conf,
            List<Map<String, Object>> localParams) {
        Object ep = firstNonNull(secret.get("endpoint"), secret.get("hosts"), secret.get("host"), secret.get("url"));
        if (ep != null && StrUtil.isNotBlank(String.valueOf(ep))) {
            String v = String.valueOf(ep).trim();
            env.put("LH_ES_ENDPOINT", v);
            upsertLocal(localParams, "LH_ES_ENDPOINT", v);
            if (conf != null && StrUtil.isBlank(conf.getStr("endpoint"))) {
                conf.set("endpoint", v);
            }
        }
        Object u = secret.get("username") != null ? secret.get("username") : secret.get("user");
        if (u != null && StrUtil.isNotBlank(String.valueOf(u))) {
            env.put("LH_ES_USER", String.valueOf(u));
            upsertLocal(localParams, "LH_ES_USER", String.valueOf(u));
        }
        if (secret.containsKey("password") && secret.get("password") != null) {
            String pw = String.valueOf(secret.get("password"));
            env.put("LH_ES_PASSWORD", pw);
            upsertLocal(localParams, "LH_ES_PASSWORD", pw);
            env.putIfAbsent("LH_ES_PASSWORD_REF", "vault://" + vaultPath + "#password");
        }
    }

    /** Doris/SR Stream Load FE HTTP（8030/8030 类），与 JDBC 9030 分轨。 */
    private void putFeHttpAliases(
            Map<String, Object> env, Map<String, Object> secret, String vaultPath, JSONObject conf,
            List<Map<String, Object>> localParams, String dsType) {
        Object fe = firstNonNull(secret.get("feHttp"), secret.get("loadUrl"), secret.get("fenodes"),
                secret.get("httpUrl"), secret.get("httpPort"));
        String host = secret.get("host") == null ? null : String.valueOf(secret.get("host")).trim();
        String httpPort = null;
        if (fe != null && StrUtil.isNotBlank(String.valueOf(fe))) {
            String raw = String.valueOf(fe).trim();
            if (raw.matches("\\d+") && StrUtil.isNotBlank(host)) {
                httpPort = host + ":" + raw;
            } else {
                httpPort = raw;
            }
        } else if (StrUtil.isNotBlank(host)) {
            httpPort = host + ":8030";
        }
        if (StrUtil.isBlank(httpPort)) {
            return;
        }
        boolean sr = dsType != null && dsType.contains("starrocks");
        String key = sr ? "LH_SR_FE_HTTP" : "LH_DORIS_FE_HTTP";
        env.put(key, httpPort);
        upsertLocal(localParams, key, httpPort);
        env.put("LH_FE_HTTP", httpPort);
        upsertLocal(localParams, "LH_FE_HTTP", httpPort);
        if (conf != null && StrUtil.isBlank(conf.getStr("loadUrl")) && StrUtil.isBlank(conf.getStr("feLoadUrl"))) {
            conf.set("loadUrl", httpPort);
        }
        if (StrUtil.isNotBlank(vaultPath)) {
            env.putIfAbsent(key + "_REF", "vault://" + vaultPath + "#feHttp");
        }
    }

    private static Object firstNonNull(Object... vals) {
        if (vals == null) {
            return null;
        }
        for (Object v : vals) {
            if (v != null) {
                return v;
            }
        }
        return null;
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
