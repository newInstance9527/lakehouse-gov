package vip.xiaonuo.lh.core.engine;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Flink SQL：从上游 JDBC 落表（{@code lh_ods_*} / {@code lh_clean_*}）写入 Iceberg。
 * <p>Flink 标识符用反引号，禁止 ANSI 双引号（sql-client 会 {@code Encountered "\""}）。</p>
 */
public final class FlinkIcebergSinkSqlCompiler {

    private FlinkIcebergSinkSqlCompiler() {
    }

    public static String compile(IgEtlNode n, JSONObject conf) {
        if (conf == null) {
            conf = new JSONObject();
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
        String s3Endpoint = firstNonBlank(conf.getStr("s3Endpoint"), conf.getStr("fs.s3a.endpoint"));
        if (StrUtil.isNotBlank(s3Endpoint)) {
            sb.append(",\n  's3.endpoint' = '").append(esc(s3Endpoint)).append("'");
            sb.append(",\n  's3.path-style-access' = 'true'");
        }
        String ak = firstNonBlank(conf.getStr("s3AccessKey"), conf.getStr("accessKey"));
        String sk = firstNonBlank(conf.getStr("s3SecretKey"), conf.getStr("secretKey"));
        if (StrUtil.isNotBlank(ak)) {
            sb.append(",\n  's3.access-key-id' = '").append(esc(ak)).append("'");
        }
        if (StrUtil.isNotBlank(sk)) {
            sb.append(",\n  's3.secret-access-key' = '").append(esc(sk)).append("'");
        } else if (StrUtil.isNotBlank(ak)) {
            sb.append(",\n  's3.secret-access-key' = '${LH_S3_SECRET_KEY}'");
        }
        sb.append("\n);\n");
        return sb.toString();
    }

    /** 演示默认 prod_catalog 一律落到湖 catalog。 */
    static String lakeCatalog(JSONObject conf) {
        String c = firstNonBlank(conf.getStr("catalog"), conf.getStr("gravCatalog"), "iceberg");
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
