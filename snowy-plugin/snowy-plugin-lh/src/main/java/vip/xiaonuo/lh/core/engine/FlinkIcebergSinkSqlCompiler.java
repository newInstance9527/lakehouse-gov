package vip.xiaonuo.lh.core.engine;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Flink SQL：写入 Iceberg。
 * <ul>
 *   <li>默认：从上游 JDBC 落表（{@code lh_ods_*} / {@code lh_clean_*}）BATCH 入湖</li>
 *   <li>{@code _lhFromKafka=true}：与 Kafka JSON 源同作业 STREAMING 入湖（source_kafka → sink_iceberg）</li>
 * </ul>
 * <p>Flink 标识符用反引号，禁止 ANSI 双引号。</p>
 */
public final class FlinkIcebergSinkSqlCompiler {

    private FlinkIcebergSinkSqlCompiler() {
    }

    public static String compile(IgEtlNode n, JSONObject conf) {
        if (conf == null) {
            conf = new JSONObject();
        }
        if (isKafkaPipeline(conf)) {
            return compileKafkaToIceberg(n, conf);
        }
        String nodeKey = n == null ? "sink" : StrUtil.blankToDefault(n.getNodeKey(), "sink");
        String catalog = lakeCatalog(conf);
        String schema = firstNonBlank(conf.getStr("schema"), conf.getStr("database"), conf.getStr("layer"), "ods");
        String table = LhStagingTables.bareTable(firstNonBlank(
                conf.getStr("table"), conf.getStr("target"), conf.getStr("objectName")));
        String up = firstNonBlank(conf.getStr("_lhUpstreamTable"), conf.getStr("src"));
        String landBare = LhStagingTables.bareTable(up);
        if (StrUtil.isBlank(table)) {
            return "SELECT 1 AS _lh_sink_probe";
        }
        if (StrUtil.isBlank(landBare)) {
            landBare = "lh_staging";
        }

        List<FlinkSourceSqlCompiler.Column> cols = FlinkSourceSqlCompiler.coerceColumns(conf.get("_lhColumnObjs"));
        if (cols.isEmpty()) {
            cols = FlinkSourceSqlCompiler.columnsFromConf(conf);
        }

        String srcAlias = "_lh_src_" + safeIdent(landBare);
        String sinkAlias = "_lh_sink_" + safeIdent(table);

        String landDb = firstNonBlank(
                conf.getStr("lhDatabase"),
                databasePart(up),
                conf.getStr("lhReaderDatabase"));
        if (StrUtil.isNotBlank(landDb)) {
            conf.set("lhDatabase", landDb);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("SET 'execution.runtime-mode' = 'BATCH';\n");
        sb.append("-- sink ").append(nodeKey)
                .append(" iceberg ").append(catalog).append('.').append(schema).append('.').append(table)
                .append(" ← jdbc ").append(StrUtil.blankToDefault(landDb, "?")).append('.').append(landBare)
                .append('\n');
        sb.append("-- credentials: LH_JDBC_USER / LH_JDBC_PASSWORD（读端落表）；Iceberg 走 HMS+warehouse\n");
        sb.append(FlinkSourceSqlCompiler.compileJdbcCreateTable(conf, srcAlias, landBare, cols));
        sb.append(compileIcebergCreateTable(conf, sinkAlias, catalog, schema, table, cols));
        sb.append("INSERT INTO `").append(sinkAlias).append("` SELECT * FROM `").append(srcAlias).append("`;\n");
        return sb.toString();
    }

    /**
     * Kafka JSON → Iceberg（单 Flink 流作业；勿再套 JDBC 落表）。
     */
    public static String compileKafkaToIceberg(IgEtlNode n, JSONObject conf) {
        if (conf == null) {
            conf = new JSONObject();
        }
        String nodeKey = n == null ? "sink" : StrUtil.blankToDefault(n.getNodeKey(), "sink");
        String catalog = lakeCatalog(conf);
        String schema = firstNonBlank(conf.getStr("schema"), conf.getStr("database"), conf.getStr("layer"), "ods");
        String table = LhStagingTables.bareTable(firstNonBlank(
                conf.getStr("table"), conf.getStr("target"), conf.getStr("objectName"), "pc_app_event"));
        String topic = FlinkKafkaSourceSqlCompiler.topicOf(conf);
        if (StrUtil.isBlank(topic)) {
            topic = firstNonBlank(conf.getStr("_lhKafkaTopic"), "pc_app_events");
        }
        if (StrUtil.isBlank(table)) {
            return "SELECT 1 AS _lh_sink_probe";
        }
        List<FlinkSourceSqlCompiler.Column> cols = FlinkKafkaSourceSqlCompiler.resolveColumns(conf);
        boolean pulsar = isPulsarPipeline(conf);
        if (pulsar) {
            topic = firstNonBlank(conf.getStr("_lhPulsarTopic"), FlinkPulsarSourceSqlCompiler.topicOf(conf), topic);
        }
        String kAlias = pulsar
                ? FlinkPulsarSourceSqlCompiler.pulsarAlias(topic)
                : FlinkKafkaSourceSqlCompiler.kafkaAlias(topic);
        String sinkAlias = "_lh_sink_" + safeIdent(table);

        String insertSelect = buildKafkaInsertSelect(conf, kAlias);

        StringBuilder sb = new StringBuilder();
        sb.append("SET 'execution.runtime-mode' = 'STREAMING';\n");
        sb.append("-- sink ").append(nodeKey)
                .append(" iceberg ").append(catalog).append('.').append(schema).append('.').append(table)
                .append(pulsar ? " ← pulsar " : " ← kafka ").append(topic).append('\n');
        if (conf.get("_lhStreamClean") != null || conf.get("_lhStreamMapping") != null) {
            sb.append("-- stream process: clean/mapping folded into same FLINK_STREAM job\n");
        }
        sb.append(pulsar
                ? "-- credentials: LH_PULSAR_SERVICE_URL（Vault）；Iceberg 走 HMS+warehouse\n"
                : "-- credentials: LH_KAFKA_BOOTSTRAP / SASL（Vault）；Iceberg 走 HMS+warehouse\n");
        if (pulsar) {
            sb.append(FlinkPulsarSourceSqlCompiler.compilePulsarCreateTable(conf, kAlias, topic, cols));
        } else {
            sb.append(FlinkKafkaSourceSqlCompiler.compileKafkaCreateTable(conf, kAlias, topic, cols));
        }
        sb.append(compileIcebergCreateTable(conf, sinkAlias, catalog, schema, table, cols));
        sb.append("INSERT INTO `").append(sinkAlias).append("`\n")
                .append(insertSelect.trim());
        if (!insertSelect.trim().endsWith(";")) {
            sb.append(';');
        }
        sb.append('\n');
        return sb.toString();
    }

    /**
     * Kafka 源 →（可选 clean / mapping）→ SELECT；无加工时 {@code SELECT * FROM kafka}.
     * <p>{@code _lhStreamProcess} 为从汇点向源走过的节点序列（近汇在前）；应用时倒序，贴近数据流。</p>
     */
    public static String buildKafkaInsertSelect(JSONObject conf, String kAlias) {
        String alias = StrUtil.blankToDefault(kAlias, "_lh_kfk_events");
        String from = "`" + alias.replace("`", "") + "`";
        cn.hutool.json.JSONArray chain = conf == null ? null : conf.getJSONArray("_lhStreamProcess");
        if (chain == null || chain.isEmpty()) {
            // 兼容仅塞了 clean/mapping 单对象的旧调用
            Object cleanObj = conf == null ? null : conf.get("_lhStreamClean");
            Object mapObj = conf == null ? null : conf.get("_lhStreamMapping");
            if (cleanObj == null && mapObj == null) {
                return "SELECT * FROM " + from;
            }
            chain = new cn.hutool.json.JSONArray();
            if (mapObj != null) {
                JSONObject o = new JSONObject();
                o.set("type", "mapping");
                o.set("conf", mapObj);
                chain.add(o);
            }
            if (cleanObj != null) {
                JSONObject o = new JSONObject();
                o.set("type", "clean");
                o.set("conf", cleanObj);
                chain.add(o);
            }
        }
        String select = null;
        // 近源先应用：chain 近汇在前 → 倒序
        for (int i = chain.size() - 1; i >= 0; i--) {
            JSONObject step = chain.getJSONObject(i);
            if (step == null) {
                continue;
            }
            String stepType = StrUtil.blankToDefault(step.getStr("type"), "");
            JSONObject stepConf = step.getJSONObject("conf");
            if (stepConf == null && step.get("conf") != null) {
                stepConf = JSONUtil.parseObj(String.valueOf(step.get("conf")));
            }
            if (stepConf == null) {
                continue;
            }
            String fromExpr = select == null ? from : "(" + stripTrailingSemi(select) + ") _lh_p" + i;
            if ("clean".equals(stepType)) {
                select = CleanSqlCompiler.compileStreaming(stepConf, fromExpr);
            } else if ("mapping".equals(stepType)) {
                select = MappingSqlCompiler.compile(stepConf, fromExpr);
            } else if ("transform".equals(stepType)) {
                // 流式折汇：自定义批 SQL 不进 Flink；透传上游，避免独立 Spark 任务读 _lh_kfk_*
                select = select == null ? "SELECT * FROM " + from : stripTrailingSemi(select);
            }
        }
        if (StrUtil.isBlank(select)) {
            return "SELECT * FROM " + from;
        }
        return select;
    }

    private static String stripTrailingSemi(String sql) {
        String s = StrUtil.blankToDefault(sql, "").trim();
        return s.endsWith(";") ? s.substring(0, s.length() - 1).trim() : s;
    }

    /** Kafka 或 Pulsar 流式折汇（历史名保留）。 */
    public static boolean isKafkaPipeline(JSONObject conf) {
        if (conf == null) {
            return false;
        }
        if (Boolean.TRUE.equals(conf.getBool("_lhFromKafka"))
                || "true".equalsIgnoreCase(StrUtil.blankToDefault(conf.getStr("_lhFromKafka"), ""))
                || Boolean.TRUE.equals(conf.getBool("_lhFromPulsar"))
                || "true".equalsIgnoreCase(StrUtil.blankToDefault(conf.getStr("_lhFromPulsar"), ""))) {
            return true;
        }
        String up = StrUtil.blankToDefault(conf.getStr("_lhUpstreamTable"), "");
        return up.startsWith("_lh_kfk_") || up.startsWith("_lh_psr_");
    }

    public static boolean isPulsarPipeline(JSONObject conf) {
        if (conf == null) {
            return false;
        }
        if (Boolean.TRUE.equals(conf.getBool("_lhFromPulsar"))
                || "true".equalsIgnoreCase(StrUtil.blankToDefault(conf.getStr("_lhFromPulsar"), ""))
                || "pulsar".equalsIgnoreCase(conf.getStr("_lhMqKind"))) {
            return true;
        }
        String up = StrUtil.blankToDefault(conf.getStr("_lhUpstreamTable"), "");
        return up.startsWith("_lh_psr_");
    }

    /** 流式 MQ 源节点类型（SHELL 占位 + 折入下游汇）。 */
    public static boolean isStreamMqSourceType(String nodeType) {
        return "source_kafka".equals(nodeType) || "source_pulsar".equals(nodeType);
    }

    static String compileIcebergCreateTable(
            JSONObject conf, String alias, String catalog, String schema, String table,
            List<FlinkSourceSqlCompiler.Column> columns) {
        List<FlinkSourceSqlCompiler.Column> usable = new ArrayList<>();
        if (columns != null) {
            for (FlinkSourceSqlCompiler.Column c : columns) {
                if (c != null && StrUtil.isNotBlank(c.name())) {
                    usable.add(c);
                }
            }
        }
        Set<String> pkNames = new LinkedHashSet<>();
        for (FlinkSourceSqlCompiler.Column c : usable) {
            if (c.primaryKey()) {
                pkNames.add(c.name().replace("`", ""));
            }
        }
        if (pkNames.isEmpty() && StrUtil.isNotBlank(conf.getStr("pk"))) {
            for (String pkn : conf.getStr("pk").split("[,;\\s]+")) {
                if (StrUtil.isNotBlank(pkn)) {
                    pkNames.add(pkn.trim().replace("`", ""));
                }
            }
        }
        if (usable.isEmpty()) {
            usable = List.of(new FlinkSourceSqlCompiler.Column("id", "STRING", true));
            pkNames.add("id");
        }

        String warehouse = warehouseOf(conf);
        String uri = metastoreUriOf(conf);
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS `").append(safeIdent(alias)).append("` (\n");
        for (int i = 0; i < usable.size(); i++) {
            FlinkSourceSqlCompiler.Column c = usable.get(i);
            sb.append("  `").append(c.name().replace("`", "")).append("` ")
                    .append(StrUtil.blankToDefault(c.flinkType(), "STRING"));
            boolean more = i < usable.size() - 1 || !pkNames.isEmpty();
            if (more) {
                sb.append(',');
            }
            sb.append('\n');
        }
        if (!pkNames.isEmpty()) {
            sb.append("  PRIMARY KEY (");
            int i = 0;
            for (String pkn : pkNames) {
                if (i++ > 0) {
                    sb.append(", ");
                }
                sb.append('`').append(pkn).append('`');
            }
            sb.append(") NOT ENFORCED\n");
        }
        String part = firstNonBlank(conf.getStr("partition"));
        if (StrUtil.isNotBlank(part)) {
            sb.append(") PARTITIONED BY (`").append(part.replace("`", "")).append("`) WITH (\n");
        } else {
            sb.append(") WITH (\n");
        }
        sb.append("  'connector' = 'iceberg',\n");
        sb.append("  'catalog-name' = '").append(esc(catalog)).append("',\n");
        sb.append("  'catalog-type' = 'hive',\n");
        sb.append("  'uri' = '").append(esc(uri)).append("',\n");
        sb.append("  'warehouse' = '").append(esc(warehouse)).append("',\n");
        sb.append("  'catalog-database' = '").append(esc(schema)).append("',\n");
        sb.append("  'catalog-table' = '").append(esc(table)).append("',\n");
        sb.append("  'format-version' = '").append(esc(StrUtil.blankToDefault(conf.getStr("formatVersion"), "2"))).append("'");
        // 默认走 Hadoop S3A（flink-conf + hadoop-aws）。勿默认 S3FileIO：需 AWS SDK v2，缺 jar 会 CNF。
        // 显式 conf.useS3FileIo=true 时才注入 io-impl（运维已装 iceberg-aws-bundle 时可用）。
        if (Boolean.TRUE.equals(conf.getBool("useS3FileIo"))
                || "true".equalsIgnoreCase(StrUtil.blankToDefault(conf.getStr("useS3FileIo"), ""))) {
            sb.append(",\n  'io-impl' = 'org.apache.iceberg.aws.s3.S3FileIO'");
            String s3Endpoint = firstNonBlank(
                    conf.getStr("s3Endpoint"), conf.getStr("fs.s3a.endpoint"), "http://182.44.68.9:9009");
            sb.append(",\n  's3.endpoint' = '").append(esc(s3Endpoint)).append("'");
            sb.append(",\n  's3.path-style-access' = 'true'");
            sb.append(",\n  'client.region' = '").append(esc(StrUtil.blankToDefault(conf.getStr("s3Region"), "us-east-1"))).append("'");
            sb.append(",\n  's3.region' = '").append(esc(StrUtil.blankToDefault(conf.getStr("s3Region"), "us-east-1"))).append("'");
            String ak = firstNonBlank(conf.getStr("s3AccessKey"), conf.getStr("accessKey"));
            String sk = firstNonBlank(conf.getStr("s3SecretKey"), conf.getStr("secretKey"));
            if (StrUtil.isNotBlank(ak)) {
                sb.append(",\n  's3.access-key-id' = '").append(esc(ak)).append("'");
            } else {
                sb.append(",\n  's3.access-key-id' = '${LH_S3_ACCESS_KEY}'");
            }
            if (StrUtil.isNotBlank(sk)) {
                sb.append(",\n  's3.secret-access-key' = '").append(esc(sk)).append("'");
            } else {
                sb.append(",\n  's3.secret-access-key' = '${LH_S3_SECRET_KEY}'");
            }
        }
        sb.append("\n);\n");
        return sb.toString();
    }

    /**
     * 湖 catalog 归一：演示名 {@code prod_catalog}/{@code hive} 一律落到真实湖 catalog {@code iceberg}
     *（Trino/Grav 均无 prod_catalog；autoCreate 也不建 catalog）。
     * 供 Flink 入湖 SQL、Sink 校验/建表、试跑预览共用。
     */
    public static String lakeCatalog(JSONObject conf) {
        String c = firstNonBlank(conf.getStr("catalog"), conf.getStr("gravCatalog"), "iceberg");
        return normalizeLakeCatalog(c);
    }

    /** 单字段归一（校验 / 预览解析 catalog 时用）。 */
    public static String normalizeLakeCatalog(String catalog) {
        String c = StrUtil.blankToDefault(catalog, "iceberg").trim();
        if ("prod_catalog".equalsIgnoreCase(c) || "hive".equalsIgnoreCase(c)) {
            return "iceberg";
        }
        return c;
    }

    static String warehouseOf(JSONObject conf) {
        String w = firstNonBlank(conf.getStr("warehouse"), conf.getStr("warehouseUri"), "s3a://warehouse/");
        if ("s3://iceberg-warehouse/prod".equalsIgnoreCase(w) || "s3a://iceberg-warehouse/".equalsIgnoreCase(w)) {
            return "s3a://warehouse/";
        }
        if (w.startsWith("s3://")) {
            return "s3a://" + w.substring("s3://".length());
        }
        return w;
    }

    static String metastoreUriOf(JSONObject conf) {
        String uri = firstNonBlank(
                conf.getStr("metastoreUris"),
                conf.getStr("uri"),
                conf.getStr("hiveMetastoreUri"),
                conf.getStr("metastore.uris"));
        if (StrUtil.isNotBlank(uri)) {
            if (!uri.contains("://")) {
                uri = "thrift://" + uri;
            }
            return uri;
        }
        String host = firstNonBlank(conf.getStr("metastoreHost"), conf.getStr("hmsHost"));
        String port = firstNonBlank(conf.getStr("metastorePort"), "9083");
        if (StrUtil.isNotBlank(host)) {
            return "thrift://" + host + ":" + port;
        }
        return "thrift://10.0.0.34:9083";
    }

    private static String databasePart(String qualified) {
        if (StrUtil.isBlank(qualified) || !qualified.contains(".")) {
            return null;
        }
        String t = qualified.replace("`", "").replace("\"", "").trim();
        int dot = t.indexOf('.');
        return dot > 0 ? t.substring(0, dot) : null;
    }

    private static String safeIdent(String s) {
        return StrUtil.blankToDefault(s, "x").replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private static String esc(String s) {
        return StrUtil.blankToDefault(s, "").replace("'", "''");
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
}
