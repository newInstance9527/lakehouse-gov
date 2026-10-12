package vip.xiaonuo.lh.core.engine;

import cn.hutool.core.util.StrUtil;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlDag;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlEdge;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Portal DAG to DolphinScheduler workflow projection.
 * Staging tables: source with downstream writes lh_ods_*; clean writes lh_clean_*.
 */
public final class DsWorkflowBuilder {

    private DsWorkflowBuilder() {
    }

    /**
     * @param plan EngineResolver results (nodeKey/engine/submitShape)
     */
    public static Map<String, Object> build(
            IgEtlDag dag,
            List<IgEtlNode> nodes,
            List<IgEtlEdge> edges,
            List<Map<String, Object>> plan,
            String workflowCode) {

        Map<String, Map<String, Object>> planByKey = plan == null ? Map.of() : plan.stream()
                .filter(p -> p.get("nodeKey") != null)
                .collect(Collectors.toMap(p -> String.valueOf(p.get("nodeKey")), p -> p, (a, b) -> a, LinkedHashMap::new));

        Map<String, List<String>> preds = new LinkedHashMap<>();
        for (IgEtlNode n : nodes) {
            preds.put(n.getNodeKey(), new ArrayList<>());
        }
        List<Map<String, Object>> relation = new ArrayList<>();
        if (edges != null) {
            for (IgEtlEdge e : edges) {
                if (preds.containsKey(e.getToNodeKey())) {
                    preds.get(e.getToNodeKey()).add(e.getFromNodeKey());
                }
                Map<String, Object> rel = new LinkedHashMap<>();
                rel.put("from", e.getFromNodeKey());
                rel.put("to", e.getToNodeKey());
                rel.put("label", StrUtil.nullToEmpty(e.getLabel()));
                relation.add(rel);
            }
        }

        List<Map<String, Object>> tasks = new ArrayList<>();
        List<Map<String, Object>> locations = new ArrayList<>();
        int codeSeed = 1;
        Map<String, Long> taskCodeByNode = new LinkedHashMap<>();
        for (IgEtlNode n : nodes) {
            taskCodeByNode.put(n.getNodeKey(),
                    (long) (codeSeed++ * 100_000_000L + Math.abs(n.getNodeKey().hashCode() % 100_000_000)));
        }

        Map<String, IgEtlNode> byKey = nodes.stream()
                .collect(Collectors.toMap(IgEtlNode::getNodeKey, n -> n, (a, b) -> a, LinkedHashMap::new));

        Map<String, List<String>> succs = new LinkedHashMap<>();
        for (IgEtlNode n : nodes) {
            succs.put(n.getNodeKey(), new ArrayList<>());
        }
        if (edges != null) {
            for (IgEtlEdge e : edges) {
                if (succs.containsKey(e.getFromNodeKey())) {
                    succs.get(e.getFromNodeKey()).add(e.getToNodeKey());
                }
            }
        }

        // Output table per node: source->lh_ods_*; clean->lh_clean_*
        // Source nodes first so clean can derive name from upstream lh_ods_* (avoid lh_clean_t)
        Map<String, String> outputTableByNode = new LinkedHashMap<>();
        List<IgEtlNode> ordered = new ArrayList<>(nodes);
        ordered.sort((a, b) -> Integer.compare(rankNodeType(a.getNodeType()), rankNodeType(b.getNodeType())));
        for (IgEtlNode n : ordered) {
            cn.hutool.json.JSONObject conf = cn.hutool.json.JSONUtil.parseObj(
                    StrUtil.blankToDefault(n.getConfJson(), "{}"));
            String database = firstNonBlank(conf.getStr("database"), conf.getStr("schema"), conf.getStr("lhDatabase"));
            String bare = LhStagingTables.bareTable(firstNonBlank(
                    conf.getStr("table"), conf.getStr("src"), conf.getStr("target"), conf.getStr("objectName"),
                    conf.getStr("topic")));
            String type = StrUtil.blankToDefault(n.getNodeType(), "");
            if (FlinkIcebergSinkSqlCompiler.isStreamMqSourceType(type)) {
                String topic = firstNonBlank(conf.getStr("topic"), conf.getStr("table"), bare);
                outputTableByNode.put(n.getNodeKey(), streamMqAlias(type, topic));
            } else if (IgEtlNodeTypes.SOURCE.contains(type) && !succs.getOrDefault(n.getNodeKey(), List.of()).isEmpty()) {
                outputTableByNode.put(n.getNodeKey(),
                        LhStagingTables.qualify(database, LhStagingTables.odsTable(bare)));
            } else if ("clean".equals(type) || "mapping".equals(type) || "transform".equals(type)) {
                String kafkaUp = null;
                for (IgEtlEdge e : edges == null ? List.<IgEtlEdge>of() : edges) {
                    if (!n.getNodeKey().equals(e.getToNodeKey())) {
                        continue;
                    }
                    String upOut = outputTableByNode.get(e.getFromNodeKey());
                    if (StrUtil.isNotBlank(upOut) && upOut.startsWith("_lh_kfk_")) {
                        kafkaUp = upOut;
                        break;
                    }
                    IgEtlNode up = byKey.get(e.getFromNodeKey());
                    if (up != null && FlinkIcebergSinkSqlCompiler.isStreamMqSourceType(up.getNodeType())) {
                        cn.hutool.json.JSONObject uc = cn.hutool.json.JSONUtil.parseObj(
                                StrUtil.blankToDefault(up.getConfJson(), "{}"));
                        kafkaUp = streamMqAlias(up.getNodeType(),
                                firstNonBlank(uc.getStr("topic"), uc.getStr("table")));
                        break;
                    }
                }
                if (StrUtil.isNotBlank(kafkaUp)) {
                    // 流式：clean/mapping 不落 lh_clean_*，逻辑名沿用 Kafka alias，加工折叠进 sink
                    outputTableByNode.put(n.getNodeKey(), kafkaUp);
                } else if ("clean".equals(type)) {
                    String cleanOut = null;
                    for (IgEtlEdge e : edges == null ? List.<IgEtlEdge>of() : edges) {
                        if (!n.getNodeKey().equals(e.getToNodeKey())) {
                            continue;
                        }
                        String upOut = outputTableByNode.get(e.getFromNodeKey());
                        if (StrUtil.isNotBlank(upOut)) {
                            cleanOut = LhStagingTables.qualify(database,
                                    LhStagingTables.cleanTableFromUpstream(upOut));
                            break;
                        }
                        IgEtlNode up = byKey.get(e.getFromNodeKey());
                        if (up != null) {
                            cn.hutool.json.JSONObject uc = cn.hutool.json.JSONUtil.parseObj(
                                    StrUtil.blankToDefault(up.getConfJson(), "{}"));
                            String upBare = LhStagingTables.bareTable(firstNonBlank(
                                    uc.getStr("table"), uc.getStr("src"), uc.getStr("objectName")));
                            if (StrUtil.isNotBlank(upBare)) {
                                cleanOut = LhStagingTables.qualify(database,
                                        LhStagingTables.cleanTable(upBare));
                                break;
                            }
                        }
                    }
                    if (StrUtil.isBlank(cleanOut) && StrUtil.isNotBlank(bare)) {
                        cleanOut = LhStagingTables.qualify(database, LhStagingTables.cleanTable(bare));
                    }
                    if (StrUtil.isBlank(cleanOut) || isPlaceholderClean(cleanOut)) {
                        cleanOut = LhStagingTables.qualify(database, LhStagingTables.cleanTable("staging"));
                    }
                    outputTableByNode.put(n.getNodeKey(), cleanOut);
                } else if (!succs.getOrDefault(n.getNodeKey(), List.of()).isEmpty()) {
                    String outBare = StrUtil.isNotBlank(conf.getStr("target")) || StrUtil.isNotBlank(conf.getStr("destTable"))
                            ? LhStagingTables.bareTable(firstNonBlank(conf.getStr("target"), conf.getStr("destTable")))
                            : LhStagingTables.cleanTable(bare);
                    outputTableByNode.put(n.getNodeKey(), LhStagingTables.qualify(database, outBare));
                }
            } else if (type.startsWith("sink_")) {
                outputTableByNode.put(n.getNodeKey(), LhStagingTables.qualify(database,
                        LhStagingTables.bareTable(firstNonBlank(conf.getStr("table"), conf.getStr("target"), bare))));
            }
        }

        for (IgEtlNode n : nodes) {
            Map<String, Object> p = planByKey.getOrDefault(n.getNodeKey(), Map.of());
            String engine = str(p.get("engine"), StrUtil.blankToDefault(n.getResolvedEngine(), "ds_sql"));
            String submitShape = str(p.get("submitShape"), submitShapeOf(engine));
            String dsTaskType = mapDsTaskType(n.getNodeType(), engine, submitShape);

            Map<String, Object> task = new LinkedHashMap<>();
            long taskCode = taskCodeByNode.get(n.getNodeKey());
            task.put("code", taskCode);
            // DS 任务实例 name 必须可回查到 nodeKey；仅用人名时门户拉日志会 no_ds_task_instance
            task.put("name", dsTaskDisplayName(n));
            task.put("nodeKey", n.getNodeKey());
            task.put("nodeType", n.getNodeType());
            task.put("engine", engine);
            task.put("rule", p.get("rule"));
            task.put("submitShape", submitShape);
            task.put("description", StrUtil.blankToDefault(n.getMeta(), ""));
            task.put("flag", "YES");
            task.put("taskPriority", "MEDIUM");
            task.put("failRetryTimes", 3);
            task.put("failRetryInterval", 3);
            task.put("delayTime", 0);

            List<String> preKeys = preds.getOrDefault(n.getNodeKey(), List.of());
            task.put("preTasks", preKeys);
            task.put("preTaskCodes", preKeys.stream().map(taskCodeByNode::get).filter(c -> c != null).toList());

            cn.hutool.json.JSONObject conf = cn.hutool.json.JSONUtil.parseObj(
                    StrUtil.blankToDefault(n.getConfJson(), "{}"));
            String type = StrUtil.blankToDefault(n.getNodeType(), "");
            String database = firstNonBlank(conf.getStr("database"), conf.getStr("schema"), conf.getStr("lhDatabase"));
            String bare = LhStagingTables.bareTable(firstNonBlank(
                    conf.getStr("table"), conf.getStr("src"), conf.getStr("objectName"), conf.getStr("topic")));
            // Kafka 流式入湖：不落 JDBC lh_ods_*；下游 sink_iceberg 同作业消费 Topic
            if (FlinkIcebergSinkSqlCompiler.isStreamMqSourceType(type)) {
                String topic = firstNonBlank(conf.getStr("topic"), conf.getStr("table"), bare);
                String kAlias = streamMqAlias(type, topic);
                if ("source_pulsar".equals(type)) {
                    conf.set("_lhFromPulsar", true);
                    conf.set("_lhMqKind", "pulsar");
                    conf.set("_lhPulsarTopic", topic);
                    conf.set("_lhPulsarAlias", kAlias);
                } else {
                    conf.set("_lhKafkaTopic", topic);
                    conf.set("_lhKafkaAlias", kAlias);
                }
                outputTableByNode.put(n.getNodeKey(), kAlias);
                n.setConfJson(conf.toString());
            } else if (IgEtlNodeTypes.SOURCE.contains(type)
                    && !succs.getOrDefault(n.getNodeKey(), List.of()).isEmpty()) {
                String land = LhStagingTables.qualify(database, LhStagingTables.odsTable(bare));
                conf.set("_lhLandingTable", land);
                conf.set("_lhBoundedChain", true);
                n.setConfJson(conf.toString());
            }
            if ("clean".equals(type) || "mapping".equals(type) || "transform".equals(type)) {
                boolean onKafka = false;
                String kafkaAlias = null;
                for (String pk : preKeys) {
                    String upOut = outputTableByNode.get(pk);
                    if (StrUtil.isNotBlank(upOut)
                            && (upOut.startsWith("_lh_kfk_") || upOut.startsWith("_lh_psr_"))) {
                        onKafka = true;
                        kafkaAlias = upOut;
                        break;
                    }
                    IgEtlNode up = byKey.get(pk);
                    if (up != null && FlinkIcebergSinkSqlCompiler.isStreamMqSourceType(up.getNodeType())) {
                        onKafka = true;
                        cn.hutool.json.JSONObject uc = cn.hutool.json.JSONUtil.parseObj(
                                StrUtil.blankToDefault(up.getConfJson(), "{}"));
                        kafkaAlias = streamMqAlias(up.getNodeType(),
                                firstNonBlank(uc.getStr("_lhPulsarTopic"), uc.getStr("_lhKafkaTopic"),
                                        uc.getStr("topic"), uc.getStr("table")));
                        break;
                    }
                }
                if (onKafka) {
                    conf.set("_lhOnKafkaPipeline", true);
                    conf.set("_lhKafkaAlias", kafkaAlias);
                    outputTableByNode.put(n.getNodeKey(), kafkaAlias);
                    n.setConfJson(conf.toString());
                    dsTaskType = "SHELL";
                } else if ("clean".equals(type)) {
                    String cleanOut = outputTableByNode.get(n.getNodeKey());
                    if (StrUtil.isBlank(cleanOut) || isPlaceholderClean(cleanOut)) {
                        for (String pk : preKeys) {
                            String upOut = outputTableByNode.get(pk);
                            if (StrUtil.isNotBlank(upOut)) {
                                cleanOut = LhStagingTables.qualify(database,
                                        LhStagingTables.cleanTableFromUpstream(upOut));
                                break;
                            }
                            IgEtlNode up = byKey.get(pk);
                            if (up != null) {
                                cn.hutool.json.JSONObject uc = cn.hutool.json.JSONUtil.parseObj(
                                        StrUtil.blankToDefault(up.getConfJson(), "{}"));
                                String upBare = LhStagingTables.bareTable(firstNonBlank(
                                        uc.getStr("table"), uc.getStr("src"), uc.getStr("objectName")));
                                if (StrUtil.isNotBlank(upBare)) {
                                    cleanOut = LhStagingTables.qualify(database,
                                            LhStagingTables.cleanTable(upBare));
                                    break;
                                }
                            }
                        }
                    }
                    if (StrUtil.isBlank(cleanOut) || isPlaceholderClean(cleanOut)) {
                        if (StrUtil.isNotBlank(bare)) {
                            cleanOut = LhStagingTables.qualify(database, LhStagingTables.cleanTable(bare));
                        } else {
                            cleanOut = LhStagingTables.qualify(database, LhStagingTables.cleanTable("staging"));
                        }
                    }
                    conf.set("_lhCleanTable", cleanOut);
                    outputTableByNode.put(n.getNodeKey(), cleanOut);
                    n.setConfJson(conf.toString());
                }
            }

            String upstreamTable = null;
            for (String pk : preKeys) {
                if (outputTableByNode.containsKey(pk)) {
                    upstreamTable = outputTableByNode.get(pk);
                    break;
                }
            }
            if (StrUtil.isBlank(upstreamTable)) {
                upstreamTable = resolveUpstreamTable(n.getNodeKey(), preKeys, byKey);
            }
            String upstreamDsId = resolveUpstreamDsId(preKeys, byKey, preds);
            if (StrUtil.isNotBlank(upstreamDsId)) {
                conf.set("_lhUpstreamDsId", upstreamDsId);
                // clean/transform ?????? sink ????? conf
                if ("clean".equals(type) || "transform".equals(type) || "mapping".equals(type)
                        || type.startsWith("sink_")) {
                    n.setConfJson(conf.toString());
                }
            }
            // sink_* ← source_kafka（可穿越 clean/mapping）：折入汇点，单 FLINK_STREAM
            if ("sink_iceberg".equals(type) || "sink_ck".equals(type) || "sink_kafka".equals(type)) {
                mergeKafkaUpstreamIntoSink(conf, preKeys, byKey, preds);
                n.setConfJson(conf.toString());
                if (FlinkIcebergSinkSqlCompiler.isKafkaPipeline(conf)) {
                    dsTaskType = "FLINK_STREAM";
                    engine = "flink";
                    task.put("engine", engine);
                }
            }
            Map<String, Object> taskParams = DsTaskScriptBuilder.buildTaskParams(n, engine, dsTaskType, upstreamTable);
            if (StrUtil.isNotBlank(upstreamDsId)) {
                taskParams.put("lhUpstreamDsId", upstreamDsId);
            }
            if (StrUtil.isNotBlank(upstreamTable)) {
                taskParams.put("lhUpstreamTable", upstreamTable);
            }
            if (StrUtil.isNotBlank(conf.getStr("_lhLandingTable"))) {
                taskParams.put("lhLandingTable", conf.getStr("_lhLandingTable"));
            }
            if (StrUtil.isNotBlank(conf.getStr("_lhCleanTable"))) {
                taskParams.put("lhCleanTable", conf.getStr("_lhCleanTable"));
            }
            if (FlinkIcebergSinkSqlCompiler.isStreamMqSourceType(type)) {
                String topic = firstNonBlank(conf.getStr("_lhPulsarTopic"), conf.getStr("_lhKafkaTopic"),
                        conf.getStr("topic"));
                taskParams.put("rawScript",
                        "#!/bin/bash\necho \"[lh] " + type + " bound topic=" + topic
                                + " (ingest runs on downstream sink_* FLINK_STREAM)\"\nexit 0\n");
            }
            if ("source_rabbitmq".equals(type)) {
                String queue = firstNonBlank(conf.getStr("queue"), conf.getStr("table"), conf.getStr("objectName"), "q");
                String vhost = firstNonBlank(conf.getStr("vhost"), "/");
                taskParams.put("rawScript", rabbitmqSourceShell(queue, vhost));
                dsTaskType = "SHELL";
            }
            if (("clean".equals(type) || "mapping".equals(type) || "transform".equals(type))
                    && Boolean.TRUE.equals(conf.getBool("_lhOnKafkaPipeline"))) {
                taskParams.put("rawScript",
                        "#!/bin/bash\necho \"[lh] " + type
                                + " on kafka pipeline (folded into downstream sink_* FLINK_STREAM)\"\nexit 0\n");
                dsTaskType = "SHELL";
            }
            if ("datax".equalsIgnoreCase(engine) || "DS_SHELL_DATAX".equals(submitShape)
                    || "DATAX".equals(dsTaskType)) {
                if (StrUtil.isNotBlank(upstreamTable)) {
                    conf.set("_lhUpstreamTable", upstreamTable);
                }
                if (StrUtil.isNotBlank(upstreamDsId)) {
                    conf.set("_lhUpstreamDsId", upstreamDsId);
                }
                taskParams.put("lhDataxJob", DataxJobBuilder.buildJob(n, conf));
                taskParams.put("rawScript", DataxJobBuilder.buildShell(n, conf));
                dsTaskType = "DATAX";
            }
            task.put("taskType", dsTaskType);
            if ("FLINK".equals(dsTaskType) || "FLINK_STREAM".equals(dsTaskType)
                    || "SPARK".equals(dsTaskType) || "DATAX".equals(dsTaskType)) {
                task.put("timeoutFlag", "OPEN");
                task.put("timeoutNotifyStrategy", "FAILED");
                // 流作业勿短超时；批/DataX 保持 30 分钟门闩
                task.put("timeout", "FLINK_STREAM".equals(dsTaskType) ? 0 : 30);
                if ("FLINK_STREAM".equals(dsTaskType)) {
                    task.put("timeoutFlag", "CLOSE");
                }
            } else {
                task.put("timeoutFlag", "CLOSE");
                task.put("timeoutNotifyStrategy", "");
                task.put("timeout", 0);
            }
            task.put("taskParams", taskParams);
            tasks.add(task);

            Map<String, Object> loc = new LinkedHashMap<>();
            loc.put("taskCode", taskCode);
            loc.put("nodeKey", n.getNodeKey());
            loc.put("x", n.getPosX() == null ? 0 : n.getPosX());
            loc.put("y", n.getPosY() == null ? 0 : n.getPosY());
            locations.add(loc);
        }

        Map<String, Object> wf = new LinkedHashMap<>();
        wf.put("workflowCode", workflowCode);
        wf.put("name", StrUtil.blankToDefault(dag.getName(), dag.getDagCode()));
        wf.put("dagCode", dag.getDagCode());
        wf.put("dagId", dag.getId());
        wf.put("description", StrUtil.nullToEmpty(dag.getDescription()));
        wf.put("cron", StrUtil.blankToDefault(dag.getCron(), "0 2 * * *"));
        wf.put("tenantCode", "root");
        wf.put("executionType", "PARALLEL");
        wf.put("timeout", 0);
        wf.put("globalParams", List.of());
        wf.put("tasks", tasks);
        wf.put("taskRelation", relation);
        wf.put("locations", locations);
        wf.put("taskCount", tasks.size());
        wf.put("edgeCount", relation.size());
        return wf;
    }

    /**
     * source_kafka 本身用短 SHELL 占位（流作业永不结束，不能作为下游依赖门闩）；
     * 真正消费在 sink_iceberg 的 FLINK_STREAM 脚本里。
     */
    static String mapDsTaskType(String nodeType, String engine, String submitShape) {
        if ("condition".equals(nodeType)) {
            return "SWITCH";
        }
        if ("parallel".equals(nodeType) || "union".equals(nodeType)) {
            return "DEPENDENT";
        }
        if ("quality".equals(nodeType) || "sink_bi".equals(nodeType) || "source_rabbitmq".equals(nodeType)) {
            return "SHELL";
        }
        if (FlinkIcebergSinkSqlCompiler.isStreamMqSourceType(nodeType)) {
            return "SHELL";
        }
        if ("DS_SQL".equals(submitShape) || "ds_sql".equalsIgnoreCase(engine)) {
            return "SQL";
        }
        if ("flink".equalsIgnoreCase(engine)) {
            return "FLINK";
        }
        if ("spark".equalsIgnoreCase(engine)) {
            return "SPARK";
        }
        if ("datax".equalsIgnoreCase(engine) || "DS_SHELL_DATAX".equals(submitShape)) {
            return "DATAX";
        }
        return "SHELL";
    }

    static String submitShapeOf(String engine) {
        if ("flink".equalsIgnoreCase(engine)) {
            return "DS_FLINK_OR_SHELL";
        }
        if ("spark".equalsIgnoreCase(engine)) {
            return "DS_SPARK_OR_SHELL_SUBMIT";
        }
        if ("datax".equalsIgnoreCase(engine)) {
            return "DS_SHELL_DATAX";
        }
        if ("ds_sql".equalsIgnoreCase(engine)) {
            return "DS_SQL";
        }
        return "DS_SHELL";
    }

    /**
     * 沿入边向上找 source_kafka（可穿越 clean/mapping），并把加工 conf 折入汇点。
     */
    static void mergeKafkaUpstreamIntoSink(
            cn.hutool.json.JSONObject sinkConf, List<String> preKeys, Map<String, IgEtlNode> byKey,
            Map<String, List<String>> preds) {
        if (sinkConf == null || preKeys == null || byKey == null) {
            return;
        }
        java.util.ArrayDeque<String> q = new java.util.ArrayDeque<>(preKeys);
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        // 近汇在前：sink←clean←mapping←kafka → [clean, mapping]
        cn.hutool.json.JSONArray process = new cn.hutool.json.JSONArray();
        IgEtlNode kafkaNode = null;
        while (!q.isEmpty()) {
            String pk = q.poll();
            if (pk == null || !seen.add(pk)) {
                continue;
            }
            IgEtlNode up = byKey.get(pk);
            if (up == null) {
                continue;
            }
            String ut = StrUtil.blankToDefault(up.getNodeType(), "");
            if (FlinkIcebergSinkSqlCompiler.isStreamMqSourceType(ut)) {
                kafkaNode = up;
                break;
            }
            if ("clean".equals(ut) || "mapping".equals(ut) || "transform".equals(ut)) {
                cn.hutool.json.JSONObject step = new cn.hutool.json.JSONObject();
                step.set("type", ut);
                step.set("conf", cn.hutool.json.JSONUtil.parseObj(
                        StrUtil.blankToDefault(up.getConfJson(), "{}")));
                process.add(step);
            }
            if (("clean".equals(ut) || "mapping".equals(ut) || "transform".equals(ut))
                    && preds != null && preds.get(pk) != null) {
                q.addAll(preds.get(pk));
            }
        }
        if (kafkaNode != null) {
            cn.hutool.json.JSONObject kc = cn.hutool.json.JSONUtil.parseObj(
                    StrUtil.blankToDefault(kafkaNode.getConfJson(), "{}"));
            boolean pulsar = "source_pulsar".equals(kafkaNode.getNodeType());
            sinkConf.set("_lhFromKafka", !pulsar);
            sinkConf.set("_lhFromPulsar", pulsar);
            sinkConf.set("_lhMqKind", pulsar ? "pulsar" : "kafka");
            // 源 Topic：Kafka→_lhKafkaTopic；Pulsar→_lhPulsarTopic；sink_kafka 的 topic 保留为目标
            String srcTopic = firstNonBlank(kc.getStr("topic"), kc.getStr("table"),
                    kc.getStr("_lhPulsarTopic"), kc.getStr("_lhKafkaTopic"), kc.getStr("src"));
            if (StrUtil.isNotBlank(srcTopic)) {
                if (pulsar) {
                    sinkConf.set("_lhPulsarTopic", srcTopic);
                } else {
                    sinkConf.set("_lhKafkaTopic", srcTopic);
                }
            }
            if (pulsar) {
                copyIfAbsent(sinkConf, kc, "serviceUrl", "adminUrl", "host", "port", "subscription");
            } else {
                copyIfAbsent(sinkConf, kc, "bootstrap", "bootstrapServers", "host", "port");
            }
            // 禁止把 user/password 拷进汇点 conf（会进 rawScript）；SASL 走 LH_KAFKA_*
            copyIfAbsent(sinkConf, kc, "groupId", "startupMode", "valueFormat",
                    "security", "saslMech", "schemaTemplate");
            String sec = firstNonBlank(kc.getStr("security"), sinkConf.getStr("security"));
            if ((sec != null && sec.toUpperCase(java.util.Locale.ROOT).contains("SASL"))
                    || StrUtil.isNotBlank(kc.getStr("saslMech"))
                    || StrUtil.isNotBlank(kc.getStr("user")) || StrUtil.isNotBlank(kc.getStr("username"))) {
                sinkConf.set("_lhKafkaSasl", true);
            }
            if (StrUtil.isBlank(sinkConf.getStr("topic")) && StrUtil.isNotBlank(srcTopic)) {
                sinkConf.set("topic", srcTopic);
            }
            if (StrUtil.isBlank(sinkConf.getStr("dsId")) && StrUtil.isNotBlank(kc.getStr("dsId"))) {
                sinkConf.set("_lhUpstreamDsId", kc.getStr("dsId"));
                sinkConf.set("readerDsId", kc.getStr("dsId"));
            } else if (StrUtil.isNotBlank(kc.getStr("dsId"))) {
                sinkConf.set("_lhUpstreamDsId",
                        firstNonBlank(sinkConf.getStr("_lhUpstreamDsId"), kc.getStr("dsId")));
                sinkConf.set("readerDsId",
                        firstNonBlank(sinkConf.getStr("readerDsId"), kc.getStr("dsId")));
            }
            String alias = firstNonBlank(
                    kc.getStr("_lhPulsarAlias"), kc.getStr("_lhKafkaAlias"),
                    streamMqAlias(kafkaNode.getNodeType(),
                            firstNonBlank(srcTopic, sinkConf.getStr("topic"))));
            sinkConf.set("_lhUpstreamTable", alias);
            if (StrUtil.isBlank(sinkConf.getStr("schemaTemplate"))) {
                sinkConf.set("schemaTemplate",
                        StrUtil.blankToDefault(kc.getStr("schemaTemplate"), "analytics"));
            }
        }
        if (!process.isEmpty()) {
            sinkConf.set("_lhStreamProcess", process);
            // 兼容旧字段：便于日志/调试
            for (int i = 0; i < process.size(); i++) {
                cn.hutool.json.JSONObject step = process.getJSONObject(i);
                if ("clean".equals(step.getStr("type"))) {
                    sinkConf.set("_lhStreamClean", step.get("conf"));
                } else if ("mapping".equals(step.getStr("type"))) {
                    sinkConf.set("_lhStreamMapping", step.get("conf"));
                } else if ("transform".equals(step.getStr("type"))) {
                    sinkConf.set("_lhStreamTransform", step.get("conf"));
                }
            }
        }
    }

    private static void copyIfAbsent(cn.hutool.json.JSONObject to, cn.hutool.json.JSONObject from,
                                     String... keys) {
        if (to == null || from == null || keys == null) {
            return;
        }
        for (String k : keys) {
            if (StrUtil.isBlank(to.getStr(k)) && StrUtil.isNotBlank(from.getStr(k))) {
                to.set(k, from.getStr(k));
            }
        }
    }

    static String streamMqAlias(String nodeType, String topic) {
        if ("source_pulsar".equals(nodeType)) {
            return FlinkPulsarSourceSqlCompiler.pulsarAlias(topic);
        }
        return FlinkKafkaSourceSqlCompiler.kafkaAlias(topic);
    }

    /** DS 任务名：始终带上 nodeKey，便于实例日志按 key 回查。 */
    static String dsTaskDisplayName(IgEtlNode n) {
        String key = StrUtil.blankToDefault(n == null ? null : n.getNodeKey(), "task").trim();
        String label = StrUtil.blankToDefault(n == null ? null : n.getName(), "").trim();
        if (StrUtil.isBlank(label) || label.equals(key) || label.contains(key)) {
            return key;
        }
        String combined = key + " · " + label;
        return combined.length() > 64 ? key : combined;
    }

    private static String resolveUpstreamTable(
            String nodeKey, List<String> preKeys, Map<String, IgEtlNode> byKey) {
        for (String pk : preKeys) {
            IgEtlNode p = byKey.get(pk);
            if (p == null) {
                continue;
            }
            cn.hutool.json.JSONObject conf = cn.hutool.json.JSONUtil.parseObj(
                    StrUtil.blankToDefault(p.getConfJson(), "{}"));
            String t = firstNonBlank(
                    conf.getStr("_lhLandingTable"),
                    conf.getStr("_lhCleanTable"),
                    conf.getStr("table"),
                    conf.getStr("src"),
                    conf.getStr("target"),
                    conf.getStr("destTable"),
                    conf.getStr("objectName"),
                    conf.getStr("_lhUpstreamTable"));
            if (StrUtil.isBlank(t)) {
                Object tables = conf.get("tables");
                if (tables instanceof cn.hutool.json.JSONArray) {
                    cn.hutool.json.JSONArray arr = (cn.hutool.json.JSONArray) tables;
                    if (!arr.isEmpty()) {
                        t = arr.getStr(0);
                    }
                }
            }
            if (StrUtil.isBlank(t)) {
                continue;
            }
            String database = StrUtil.trim(conf.getStr("database"));
            String schema = StrUtil.trim(firstNonBlank(conf.getStr("schema"), conf.getStr("layer")));
            if (t.contains(".")) {
                return t;
            }
            if (StrUtil.isNotBlank(database) && StrUtil.isNotBlank(schema)) {
                return database + "." + schema + "." + t;
            }
            if (StrUtil.isNotBlank(database)) {
                return database + "." + t;
            }
            if (StrUtil.isNotBlank(schema)) {
                return schema + "." + t;
            }
            return t;
        }
        return null;
    }

    /** ?? source ????? clean????????? lh_clean_t */
    private static int rankNodeType(String type) {
        String t = StrUtil.blankToDefault(type, "");
        if (IgEtlNodeTypes.SOURCE.contains(t)) {
            return 0;
        }
        if ("clean".equals(t) || "transform".equals(t) || "mapping".equals(t)) {
            return 1;
        }
        if (t.startsWith("sink_")) {
            return 2;
        }
        return 3;
    }

    /** ?????????????? bug / ?????? */
    private static boolean isPlaceholderClean(String qualifiedOrBare) {
        String b = LhStagingTables.bareTable(qualifiedOrBare).toLowerCase();
        return "lh_clean_t".equals(b) || "lh_clean_staging".equals(b) || "t".equals(b);
    }

    private static String resolveUpstreamDsId(List<String> preKeys, Map<String, IgEtlNode> byKey,
                                              Map<String, List<String>> preds) {
        // sink?clean?source?clean ?? dsId?????????????? DataX ??????? Vault?
        java.util.ArrayDeque<String> q = new java.util.ArrayDeque<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        if (preKeys != null) {
            for (String pk : preKeys) {
                if (StrUtil.isNotBlank(pk)) {
                    q.add(pk.trim());
                }
            }
        }
        while (!q.isEmpty()) {
            String k = q.poll();
            if (!seen.add(k)) {
                continue;
            }
            IgEtlNode p = byKey.get(k);
            if (p == null) {
                continue;
            }
            cn.hutool.json.JSONObject conf = cn.hutool.json.JSONUtil.parseObj(
                    StrUtil.blankToDefault(p.getConfJson(), "{}"));
            String dsId = firstNonBlank(
                    conf.getStr("dsId"),
                    conf.getStr("readerDsId"),
                    conf.getStr("srcDsId"),
                    conf.getStr("_lhUpstreamDsId"));
            if (StrUtil.isNotBlank(dsId)) {
                return dsId;
            }
            List<String> ups = preds != null ? preds.getOrDefault(k, List.of()) : List.of();
            for (String u : ups) {
                if (StrUtil.isNotBlank(u) && !seen.contains(u)) {
                    q.add(u.trim());
                }
            }
        }
        return null;
    }

    /**
     * RabbitMQ 入湖：优先平台消费命令；否则 SHELL 占位（凭证 Vault LH_RABBIT_*）。
     * <p>queue/vhost 白名单转义，禁止注入；CONSUMER_CMD 必须为绝对路径且无空白元字符。</p>
     */
    static String rabbitmqSourceShell(String queue, String vhost) {
        String q = sanitizeRabbitToken(queue, "q");
        String vh = sanitizeRabbitVhost(vhost);
        return "#!/bin/bash\nset -euo pipefail\n"
                + "QUEUE='" + q + "'\n"
                + "VHOST='" + vh + "'\n"
                + "HOST=\"${LH_RABBIT_HOST:-}\"\n"
                + "PORT=\"${LH_RABBIT_PORT:-5672}\"\n"
                + "CMD=\"${LH_RABBIT_CONSUMER_CMD:-}\"\n"
                + "if [ -n \"$CMD\" ]; then\n"
                + "  if [[ \"$CMD\" != /* ]]; then echo '[lh] LH_RABBIT_CONSUMER_CMD must be absolute path'; exit 2; fi\n"
                + "  if [[ \"$CMD\" =~ [[:space:]\\$\\`\\;\\|\\&] ]]; then echo '[lh] LH_RABBIT_CONSUMER_CMD has unsafe chars'; exit 2; fi\n"
                + "  exec \"$CMD\" --host \"$HOST\" --port \"$PORT\""
                + " --vhost \"$VHOST\" --queue \"$QUEUE\""
                + " --user \"${LH_RABBIT_USER:-}\" --password \"${LH_RABBIT_PASSWORD:-}\"\n"
                + "fi\n"
                + "echo \"[lh] source_rabbitmq queue=$QUEUE vhost=$VHOST host=$HOST"
                + " (set LH_RABBIT_CONSUMER_CMD for real ingest)\"\n"
                + "exit 0\n";
    }

    /** AMQP queue / routing 名：字母数字 ._-: /  */
    static String sanitizeRabbitToken(String raw, String fallback) {
        String s = StrUtil.blankToDefault(raw, fallback).trim();
        if (!s.matches("[A-Za-z0-9._\\-:/]{1,200}")) {
            return fallback;
        }
        return s;
    }

    static String sanitizeRabbitVhost(String raw) {
        String s = StrUtil.blankToDefault(raw, "/").trim();
        if ("/".equals(s)) {
            return "/";
        }
        if (!s.matches("[A-Za-z0-9._\\-/]{1,200}")) {
            return "/";
        }
        return s.replace("'", "");
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

    private static String str(Object o, String def) {
        if (o == null) {
            return def;
        }
        String s = String.valueOf(o);
        return StrUtil.isBlank(s) || "null".equals(s) ? def : s;
    }
}
