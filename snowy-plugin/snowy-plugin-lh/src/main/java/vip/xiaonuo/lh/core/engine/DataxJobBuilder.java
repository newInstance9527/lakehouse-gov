package vip.xiaonuo.lh.core.engine;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * DataX job.json + Shell。
 * <p>读写端凭证拆分：{@code LH_READER_JDBC_*} / {@code LH_WRITER_JDBC_*}（兼容旧 {@code LH_JDBC_*}）。</p>
 */
public final class DataxJobBuilder {

    private DataxJobBuilder() {
    }

    public static Map<String, Object> buildJob(IgEtlNode n, JSONObject conf) {
        if (conf == null) {
            conf = JSONUtil.createObj();
        }
        String nodeType = n == null ? "" : StrUtil.blankToDefault(n.getNodeType(), "");
        boolean sinkSide = nodeType.startsWith("sink_");

        String readerType = StrUtil.blankToDefault(conf.getStr("readerType"),
                guessReader(firstNonBlank(conf.getStr("readerDsType"), conf.getStr("lhReaderDsType"),
                        sinkSide ? conf.getStr("lhUpstreamDsType") : conf.getStr("lhDsType")), nodeType));
        String writerType = StrUtil.blankToDefault(conf.getStr("writerType"),
                guessWriter(firstNonBlank(conf.getStr("writerDsType"), conf.getStr("lhWriterDsType"),
                        sinkSide ? conf.getStr("lhDsType") : conf.getStr("targetDsType")), nodeType));

        String readerTable = firstNonBlank(
                conf.getStr("readerTable"),
                sinkSide ? firstNonBlank(conf.getStr("_lhUpstreamTable"), conf.getStr("src")) : null,
                conf.getStr("table"), conf.getStr("src"), conf.getStr("objectName"));
        String writerTable = firstNonBlank(
                conf.getStr("writerTable"), conf.getStr("target"), conf.getStr("destTable"),
                sinkSide ? conf.getStr("table") : conf.getStr("target"),
                conf.getStr("table"));
        String querySql = firstNonBlank(conf.getStr("sql"), conf.getStr("query"), conf.getStr("querySql"));

        ColumnLists cols = resolveColumnLists(conf);

        Map<String, Object> readerParam = buildReaderParam(readerType, conf, cols, readerTable, querySql, sinkSide);
        Map<String, Object> writerParam = buildWriterParam(writerType, conf, cols, writerTable);

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("reader", Map.of("name", readerType, "parameter", readerParam));
        content.put("writer", Map.of("name", writerType, "parameter", writerParam));

        Map<String, Object> job = new LinkedHashMap<>();
        job.put("setting", Map.of(
                "speed", Map.of("channel", conf.getInt("channel", 2)),
                "errorLimit", Map.of("record", conf.getInt("errorLimit", 0))));
        job.put("content", List.of(content));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("job", job);
        root.put("lhNodeKey", n == null ? null : n.getNodeKey());
        root.put("lhNodeType", nodeType);
        root.put("lhReaderDsId", firstNonBlank(conf.getStr("readerDsId"), conf.getStr("srcDsId"),
                sinkSide ? conf.getStr("_lhUpstreamDsId") : conf.getStr("dsId")));
        root.put("lhWriterDsId", firstNonBlank(conf.getStr("writerDsId"), conf.getStr("targetDsId"),
                sinkSide ? conf.getStr("dsId") : conf.getStr("targetDsId")));
        return root;
    }

    public static String buildShell(IgEtlNode n, JSONObject conf) {
        Map<String, Object> job = buildJob(n, conf);
        StringBuilder sb = new StringBuilder();
        sb.append("#!/bin/bash\nset -euo pipefail\n");
        sb.append("NODE_KEY='").append(n.getNodeKey()).append("'\n");
        sb.append("DATAX_HOME=\"${LH_DATAX_HOME:-/opt/datax}\"\n");
        // 写端可回退 LH_JDBC（汇点本节点 ds）；读端禁止回退——否则 sink 会把 postgres 填进 mysqlreader
        sb.append("export LH_WRITER_JDBC_URL=\"${LH_WRITER_JDBC_URL:-${LH_JDBC_URL:-}}\"\n");
        sb.append("export LH_WRITER_JDBC_USER=\"${LH_WRITER_JDBC_USER:-${LH_JDBC_USER:-}}\"\n");
        sb.append("export LH_WRITER_JDBC_PASSWORD=\"${LH_WRITER_JDBC_PASSWORD:-${LH_JDBC_PASSWORD:-}}\"\n");
        sb.append("export LH_READER_JDBC_URL=\"${LH_READER_JDBC_URL:-}\"\n");
        sb.append("export LH_READER_JDBC_USER=\"${LH_READER_JDBC_USER:-}\"\n");
        sb.append("export LH_READER_JDBC_PASSWORD=\"${LH_READER_JDBC_PASSWORD:-}\"\n");
        appendNonJdbcEnvExports(sb);
        String readerName = extractPluginName(job, "reader");
        if (isJdbcPlugin(readerName)) {
            sb.append("if [ -z \"${LH_READER_JDBC_URL}\" ]; then echo '[lh-datax] LH_READER_JDBC_URL empty (upstream ds missing)'; exit 2; fi\n");
        } else if ("mongodbreader".equalsIgnoreCase(readerName)) {
            sb.append("export LH_MONGO_USER=\"${LH_MONGO_USER:-${LH_READER_JDBC_USER:-}}\"\n");
            sb.append("export LH_MONGO_PASSWORD=\"${LH_MONGO_PASSWORD:-${LH_READER_JDBC_PASSWORD:-}}\"\n");
        } else if (readerName != null && readerName.toLowerCase(Locale.ROOT).startsWith("hbase")) {
            sb.append("export LH_HBASE_ZK=\"${LH_HBASE_ZK:-${LH_CONN_HOST:-}}\"\n");
        }
        sb.append("JOB_FILE=$(mktemp /tmp/lh-datax-${NODE_KEY}-XXXX.json)\n");
        sb.append("cat > \"$JOB_FILE\" <<'LH_DATAX_EOF'\n");
        sb.append(JSONUtil.toJsonPrettyStr(job)).append("\n");
        sb.append("LH_DATAX_EOF\n");
        appendExpandAndRun(sb);
        return sb.toString();
    }

    /** 已有 DataX job.json 正文时生成 SHELL（发布热路径 / 原生 DATAX→SHELL）。 */
    public static String buildShellFromJobJson(String nodeKey, String jobJson) {
        StringBuilder sb = new StringBuilder();
        sb.append("#!/bin/bash\nset -euo pipefail\n");
        sb.append("NODE_KEY='").append(StrUtil.blankToDefault(nodeKey, "datax")).append("'\n");
        sb.append("DATAX_HOME=\"${LH_DATAX_HOME:-/opt/datax}\"\n");
        sb.append("export LH_WRITER_JDBC_URL=\"${LH_WRITER_JDBC_URL:-${LH_JDBC_URL:-}}\"\n");
        sb.append("export LH_WRITER_JDBC_USER=\"${LH_WRITER_JDBC_USER:-${LH_JDBC_USER:-}}\"\n");
        sb.append("export LH_WRITER_JDBC_PASSWORD=\"${LH_WRITER_JDBC_PASSWORD:-${LH_JDBC_PASSWORD:-}}\"\n");
        sb.append("export LH_READER_JDBC_URL=\"${LH_READER_JDBC_URL:-}\"\n");
        sb.append("export LH_READER_JDBC_USER=\"${LH_READER_JDBC_USER:-}\"\n");
        sb.append("export LH_READER_JDBC_PASSWORD=\"${LH_READER_JDBC_PASSWORD:-}\"\n");
        appendNonJdbcEnvExports(sb);
        // 热路径不知 reader 类型：有 URL 才校验；无 URL 时靠插件自身占位
        sb.append("JOB_FILE=$(mktemp /tmp/lh-datax-${NODE_KEY}-XXXX.json)\n");
        sb.append("cat > \"$JOB_FILE\" <<'LH_DATAX_EOF'\n");
        sb.append(StrUtil.blankToDefault(jobJson, "{\"job\":{\"content\":[]}}")).append("\n");
        sb.append("LH_DATAX_EOF\n");
        appendExpandAndRun(sb);
        return sb.toString();
    }

    private static void appendNonJdbcEnvExports(StringBuilder sb) {
        sb.append("export LH_FTP_HOST=\"${LH_FTP_HOST:-${LH_CONN_HOST:-}}\"\n");
        sb.append("export LH_FTP_USER=\"${LH_FTP_USER:-${LH_CONN_USER:-${LH_WRITER_JDBC_USER:-}}}\"\n");
        sb.append("export LH_FTP_PASSWORD=\"${LH_FTP_PASSWORD:-${LH_WRITER_JDBC_PASSWORD:-}}\"\n");
        sb.append("export LH_ES_ENDPOINT=\"${LH_ES_ENDPOINT:-${LH_WRITER_JDBC_URL:-}}\"\n");
        sb.append("export LH_OSS_ENDPOINT=\"${LH_OSS_ENDPOINT:-${LH_CONN_ENDPOINT:-}}\"\n");
        sb.append("export LH_OSS_ACCESS_ID=\"${LH_OSS_ACCESS_ID:-${LH_CONN_ACCESSKEY:-${LH_WRITER_JDBC_USER:-}}}\"\n");
        sb.append("export LH_OSS_ACCESS_KEY=\"${LH_OSS_ACCESS_KEY:-${LH_WRITER_JDBC_PASSWORD:-}}\"\n");
        sb.append("export LH_OSS_BUCKET=\"${LH_OSS_BUCKET:-}\"\n");
        sb.append("export LH_HDFS_DEFAULT_FS=\"${LH_HDFS_DEFAULT_FS:-${LH_CONN_DEFAULTFS:-}}\"\n");
        sb.append("export LH_DORIS_FE_HTTP=\"${LH_DORIS_FE_HTTP:-${LH_FE_HTTP:-}}\"\n");
        sb.append("export LH_SR_FE_HTTP=\"${LH_SR_FE_HTTP:-${LH_FE_HTTP:-}}\"\n");
        sb.append("export LH_HTTP_URL=\"${LH_HTTP_URL:-${LH_CONN_BASEURL:-}}\"\n");
        sb.append("export LH_RABBIT_HOST=\"${LH_RABBIT_HOST:-${LH_CONN_HOST:-}}\"\n");
    }

    private static void appendExpandAndRun(StringBuilder sb) {
        sb.append("# 展开环境变量占位到 job 文件（URL/密码含 & 时禁止裸 sed）\n");
        LhEnvPlaceholderExpand.appendExpandFile(sb, "JOB_FILE",
                "LH_READER_JDBC_URL", "LH_READER_JDBC_USER", "LH_READER_JDBC_PASSWORD",
                "LH_WRITER_JDBC_URL", "LH_WRITER_JDBC_USER", "LH_WRITER_JDBC_PASSWORD",
                "LH_FTP_HOST", "LH_FTP_USER", "LH_FTP_PASSWORD",
                "LH_ES_ENDPOINT",
                "LH_OSS_ENDPOINT", "LH_OSS_ACCESS_ID", "LH_OSS_ACCESS_KEY", "LH_OSS_BUCKET",
                "LH_HDFS_DEFAULT_FS",
                "LH_DORIS_FE_HTTP", "LH_SR_FE_HTTP", "LH_FE_HTTP",
                "LH_HTTP_URL", "LH_MONGO_USER", "LH_MONGO_PASSWORD", "LH_HBASE_ZK");
        sb.append("PY=\"${PYTHON_LAUNCHER:-$(command -v python3 || command -v python || true)}\"\n");
        sb.append("if [ -z \"$PY\" ]; then echo '[lh-datax] python not found'; exit 127; fi\n");
        sb.append("if [ -f \"$DATAX_HOME/bin/datax.py\" ]; then\n");
        sb.append("  \"$PY\" \"$DATAX_HOME/bin/datax.py\" \"$JOB_FILE\"\n");
        sb.append("elif command -v datax.py >/dev/null 2>&1; then\n");
        sb.append("  \"$PY\" \"$(command -v datax.py)\" \"$JOB_FILE\"\n");
        sb.append("else\n");
        sb.append("  echo \"[lh-datax] dry-run job=$JOB_FILE\"\n");
        sb.append("  cat \"$JOB_FILE\"\n");
        sb.append("fi\n");
        sb.append("rm -f \"$JOB_FILE\" \"$JOB_FILE.bak\"\n");
    }

    /**
     * 列投影：优先 {@code fieldMaps}/{@code mapList}（门户字段映射）；
     * DataX 按位置对齐，故 reader=src、writer=dst 可同名可改名。
     */
    private static ColumnLists resolveColumnLists(JSONObject conf) {
        List<String> reader = new ArrayList<>();
        List<String> writer = new ArrayList<>();

        JSONArray maps = conf.getJSONArray("fieldMaps");
        if (maps == null || maps.isEmpty()) {
            maps = conf.getJSONArray("mapList");
        }
        if (maps != null) {
            for (int i = 0; i < maps.size(); i++) {
                JSONObject m = maps.getJSONObject(i);
                if (m == null || m.getBool("skip", false)) {
                    continue;
                }
                String src = StrUtil.trim(firstNonBlank(m.getStr("src"), m.getStr("source"), m.getStr("from")));
                String dst = StrUtil.trim(firstNonBlank(m.getStr("dst"), m.getStr("target"), m.getStr("to"),
                        m.getStr("std"), src));
                if (StrUtil.isBlank(src) && StrUtil.isBlank(dst)) {
                    continue;
                }
                if (StrUtil.isBlank(src)) {
                    src = dst;
                }
                if (StrUtil.isBlank(dst)) {
                    dst = src;
                }
                reader.add(src);
                writer.add(dst);
            }
        }
        if (!reader.isEmpty()) {
            return new ColumnLists(reader, writer);
        }

        List<String> shared = new ArrayList<>();
        // 门户表单：column / columnMap 逗号分隔投影
        String columnCsv = firstNonBlank(conf.getStr("column"),
                conf.get("columnMap") instanceof String cm ? cm : null);
        if (StrUtil.isNotBlank(columnCsv) && !"*".equals(columnCsv.trim())) {
            for (String p : columnCsv.split("[,;]")) {
                if (StrUtil.isNotBlank(p)) {
                    shared.add(p.trim());
                }
            }
        }
        JSONArray rules = conf.getJSONArray("fieldRules");
        if (shared.isEmpty() && rules != null) {
            for (int i = 0; i < rules.size(); i++) {
                JSONObject r = rules.getJSONObject(i);
                if (r != null && StrUtil.isNotBlank(r.getStr("field"))) {
                    shared.add(r.getStr("field").trim());
                }
            }
        }
        JSONArray lhCols = conf.getJSONArray("_lhColumns");
        if (shared.isEmpty() && lhCols != null) {
            for (int i = 0; i < lhCols.size(); i++) {
                JSONObject c = lhCols.getJSONObject(i);
                if (c == null) {
                    continue;
                }
                String name = firstNonBlank(c.getStr("name"), c.getStr("column"), c.getStr("field"));
                if (StrUtil.isNotBlank(name)) {
                    shared.add(name.trim());
                }
            }
        }
        if (shared.isEmpty()) {
            shared.add("*");
        }
        return new ColumnLists(shared, new ArrayList<>(shared));
    }

    private record ColumnLists(List<String> reader, List<String> writer) {
    }

    static boolean isJdbcPlugin(String plugin) {
        String p = StrUtil.blankToDefault(plugin, "").toLowerCase(Locale.ROOT);
        boolean jdbcName = p.contains("mysql") || p.contains("postgres") || p.contains("oracle")
                || p.contains("sqlserver") || p.contains("clickhouse") || p.contains("rdbms")
                || p.contains("doris") || p.contains("starrocks");
        if (p.endsWith("reader")) {
            return jdbcName || p.contains("hive");
        }
        if (p.endsWith("writer")) {
            return jdbcName;
        }
        return false;
    }

    private static Map<String, Object> buildReaderParam(
            String readerType, JSONObject conf, ColumnLists cols, String readerTable, String querySql, boolean sinkSide) {
        String rt = StrUtil.blankToDefault(readerType, "").toLowerCase(Locale.ROOT);
        if ("httpreader".equals(rt)) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("url", List.of(firstNonBlank(conf.getStr("baseUrl"), conf.getStr("url"), "${LH_HTTP_URL}")));
            p.put("method", StrUtil.blankToDefault(conf.getStr("method"), "get"));
            p.put("column", cols.reader);
            return p;
        }
        if ("ftpreader".equals(rt)) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("protocol", StrUtil.blankToDefault(conf.getStr("protocol"), "sftp").toLowerCase(Locale.ROOT));
            p.put("host", firstNonBlank(conf.getStr("host"), "${LH_FTP_HOST}"));
            p.put("port", conf.getInt("port", 22));
            p.put("username", "${LH_FTP_USER}");
            p.put("password", "${LH_FTP_PASSWORD}");
            p.put("path", List.of(firstNonBlank(conf.getStr("basePath"), conf.getStr("path"), conf.getStr("remoteDir"), "/")));
            p.put("column", cols.reader);
            p.put("fieldDelimiter", StrUtil.blankToDefault(conf.getStr("fieldDelimiter"), ","));
            p.put("encoding", StrUtil.blankToDefault(conf.getStr("encoding"), "UTF-8"));
            return p;
        }
        if ("txtfilereader".equals(rt) || "hdfsreader".equals(rt)) {
            Map<String, Object> p = new LinkedHashMap<>();
            String path = firstNonBlank(conf.getStr("basePath"), conf.getStr("path"),
                    conf.getStr("bucket") != null ? conf.getStr("bucket") + "/" + StrUtil.blankToDefault(conf.getStr("basePath"), "") : null,
                    "/data/in");
            p.put("path", List.of(path));
            p.put("column", cols.reader);
            p.put("encoding", StrUtil.blankToDefault(conf.getStr("encoding"), "UTF-8"));
            p.put("fieldDelimiter", StrUtil.blankToDefault(conf.getStr("fieldDelimiter"), ","));
            if ("hdfsreader".equals(rt)) {
                p.put("defaultFS", firstNonBlank(conf.getStr("defaultFS"), conf.getStr("endpoint"),
                        "${LH_HDFS_DEFAULT_FS}", "hdfs://namenode:8020"));
                p.put("fileType", StrUtil.blankToDefault(conf.getStr("format"), "text"));
            }
            return p;
        }
        if ("ossreader".equals(rt)) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("endpoint", firstNonBlank(conf.getStr("endpoint"), "${LH_OSS_ENDPOINT}"));
            p.put("accessId", "${LH_OSS_ACCESS_ID}");
            p.put("accessKey", "${LH_OSS_ACCESS_KEY}");
            p.put("bucket", firstNonBlank(conf.getStr("bucket"), "lakehouse"));
            p.put("object", List.of(firstNonBlank(conf.getStr("basePath"), conf.getStr("object"), conf.getStr("path"), "*")));
            p.put("column", cols.reader);
            return p;
        }
        if ("mongodbreader".equals(rt)) {
            Map<String, Object> p = new LinkedHashMap<>();
            String host = firstNonBlank(conf.getStr("host"), conf.getStr("lhHost"), "127.0.0.1");
            String port = firstNonBlank(str(conf.get("port")), conf.getStr("lhPort"), "27017");
            String addr = host.contains(":") ? host : host + ":" + port;
            p.put("address", List.of(firstNonBlank(conf.getStr("address"), addr)));
            p.put("userName", "${LH_MONGO_USER}");
            p.put("userPassword", "${LH_MONGO_PASSWORD}");
            p.put("dbName", firstNonBlank(conf.getStr("database"), conf.getStr("dbName"), conf.getStr("lhDatabase"), "test"));
            p.put("collectionName", firstNonBlank(conf.getStr("collection"), conf.getStr("table"),
                    conf.getStr("objectName"), readerTable, "coll"));
            p.put("column", toMongoColumns(cols.reader));
            if (StrUtil.isNotBlank(conf.getStr("query"))) {
                p.put("query", conf.getStr("query"));
            }
            return p;
        }
        if ("hbasereader".equals(rt) || "hbasereader11".equals(rt)) {
            Map<String, Object> p = new LinkedHashMap<>();
            Map<String, Object> hbaseConfig = new LinkedHashMap<>();
            hbaseConfig.put("hbase.zookeeper.quorum",
                    firstNonBlank(conf.getStr("zkQuorum"), conf.getStr("host"), conf.getStr("lhHost"), "${LH_HBASE_ZK}"));
            String zkPort = firstNonBlank(str(conf.get("zkPort")), conf.getStr("port"), "2181");
            hbaseConfig.put("hbase.zookeeper.property.clientPort", zkPort);
            p.put("hbaseConfig", hbaseConfig);
            p.put("table", firstNonBlank(conf.getStr("table"), conf.getStr("objectName"), readerTable, "t"));
            p.put("mode", StrUtil.blankToDefault(conf.getStr("mode"), "normal"));
            p.put("column", toHbaseColumns(cols.reader, conf.getStr("columnFamily")));
            p.put("encoding", StrUtil.blankToDefault(conf.getStr("encoding"), "UTF-8"));
            return p;
        }
        // 默认 JDBC reader
        Map<String, Object> readerParam = new LinkedHashMap<>();
        readerParam.put("username", "${LH_READER_JDBC_USER}");
        readerParam.put("password", "${LH_READER_JDBC_PASSWORD}");
        readerParam.put("column", cols.reader);
        if (StrUtil.isNotBlank(querySql) && !sinkSide) {
            readerParam.put("connection", List.of(Map.of(
                    "querySql", List.of(querySql),
                    "jdbcUrl", List.of("${LH_READER_JDBC_URL}"))));
        } else {
            readerParam.put("connection", List.of(Map.of(
                    "table", List.of(StrUtil.blankToDefault(readerTable, "dual")),
                    "jdbcUrl", List.of("${LH_READER_JDBC_URL}"))));
        }
        return readerParam;
    }

    private static Map<String, Object> buildWriterParam(
            String writerType, JSONObject conf, ColumnLists cols, String writerTable) {
        String wt = StrUtil.blankToDefault(writerType, "").toLowerCase(Locale.ROOT);
        if ("elasticsearchwriter".equals(wt)) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("endpoint", firstNonBlank(conf.getStr("endpoint"), conf.getStr("hosts"), "${LH_ES_ENDPOINT}"));
            p.put("index", firstNonBlank(conf.getStr("index"), writerTable, "lh_index"));
            p.put("type", StrUtil.blankToDefault(conf.getStr("docType"), "_doc"));
            p.put("cleanup", Boolean.TRUE.equals(conf.getBool("cleanup")));
            p.put("column", toEsColumns(cols.writer));
            if (StrUtil.isNotBlank(conf.getStr("idField"))) {
                p.put("idField", conf.getStr("idField").trim());
                p.put("splitter", StrUtil.blankToDefault(conf.getStr("splitter"), ","));
            }
            return p;
        }
        if ("ftpwriter".equals(wt)) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("protocol", StrUtil.blankToDefault(conf.getStr("protocol"), "sftp").toLowerCase(Locale.ROOT));
            p.put("host", firstNonBlank(conf.getStr("host"), "${LH_FTP_HOST}"));
            p.put("port", conf.getInt("port", 22));
            p.put("username", "${LH_FTP_USER}");
            p.put("password", "${LH_FTP_PASSWORD}");
            p.put("path", firstNonBlank(conf.getStr("remoteDir"), conf.getStr("path"), "/export"));
            p.put("fileName", firstNonBlank(conf.getStr("fileName"), "export.csv"));
            p.put("writeMode", StrUtil.blankToDefault(conf.getStr("writeMode"), "truncate"));
            p.put("fieldDelimiter", StrUtil.blankToDefault(conf.getStr("fieldDelimiter"), ","));
            p.put("encoding", StrUtil.blankToDefault(conf.getStr("encoding"), "UTF-8"));
            p.put("dateFormat", "yyyy-MM-dd HH:mm:ss");
            p.put("fileFormat", StrUtil.blankToDefault(conf.getStr("format"), "csv"));
            return p;
        }
        if ("hdfswriter".equals(wt)) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("defaultFS", firstNonBlank(conf.getStr("defaultFS"), conf.getStr("endpoint"),
                    "${LH_HDFS_DEFAULT_FS}", "hdfs://namenode:8020"));
            p.put("fileType", StrUtil.blankToDefault(conf.getStr("format"), "text"));
            p.put("path", firstNonBlank(conf.getStr("basePath"), conf.getStr("path"), "/data/out"));
            p.put("fileName", firstNonBlank(conf.getStr("fileName"), "part"));
            p.put("column", cols.writer);
            p.put("writeMode", StrUtil.blankToDefault(conf.getStr("writeMode"), "append"));
            p.put("fieldDelimiter", StrUtil.blankToDefault(conf.getStr("fieldDelimiter"), ","));
            p.put("encoding", "UTF-8");
            return p;
        }
        if ("osswriter".equals(wt)) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("endpoint", firstNonBlank(conf.getStr("endpoint"), "${LH_OSS_ENDPOINT}"));
            p.put("accessId", "${LH_OSS_ACCESS_ID}");
            p.put("accessKey", "${LH_OSS_ACCESS_KEY}");
            p.put("bucket", firstNonBlank(conf.getStr("bucket"), "${LH_OSS_BUCKET}", "lakehouse"));
            p.put("object", firstNonBlank(conf.getStr("basePath"), conf.getStr("object"), conf.getStr("path"), "export/"));
            p.put("encoding", "UTF-8");
            p.put("fieldDelimiter", ",");
            p.put("writeMode", StrUtil.blankToDefault(conf.getStr("writeMode"), "truncate"));
            return p;
        }
        // Doris / StarRocks：Stream Load（DataX doriswriter / starrockswriter）
        if ("doriswriter".equals(wt) || "starrockswriter".equals(wt)) {
            return buildStreamLoadWriterParam(wt, conf, cols, writerTable);
        }
        Map<String, Object> writerParam = new LinkedHashMap<>();
        writerParam.put("username", "${LH_WRITER_JDBC_USER}");
        writerParam.put("password", "${LH_WRITER_JDBC_PASSWORD}");
        writerParam.put("column", cols.writer);
        writerParam.put("connection", List.of(Map.of(
                "table", List.of(StrUtil.blankToDefault(writerTable, "lh_sync_target")),
                "jdbcUrl", "${LH_WRITER_JDBC_URL}")));
        // 方言写入模式：mysql 系 insert/replace；oracle 常用 insert；sqlserver insert；pg 无 writeMode
        if (!"postgresqlwriter".equalsIgnoreCase(writerType)) {
            String writeMode = StrUtil.blankToDefault(conf.getStr("writeMode"),
                    "oraclewriter".equalsIgnoreCase(writerType) || "sqlserverwriter".equalsIgnoreCase(writerType)
                            ? "insert" : "insert");
            if ("replace".equalsIgnoreCase(writeMode) && !"mysqlwriter".equalsIgnoreCase(writerType)
                    && !"clickhousewriter".equalsIgnoreCase(writerType)) {
                writeMode = "insert";
            }
            writerParam.put("writeMode", writeMode);
        }
        if (StrUtil.isNotBlank(conf.getStr("preSql"))) {
            writerParam.put("preSql", List.of(conf.getStr("preSql")));
        } else if ("postgresqlwriter".equalsIgnoreCase(writerType)
                && !Boolean.TRUE.equals(conf.getBool("skipPreDelete"))) {
            String t = StrUtil.blankToDefault(writerTable, "lh_sync_target");
            if (t.matches("[A-Za-z0-9_\\.]+")) {
                writerParam.put("preSql", List.of("DELETE FROM " + t));
            }
        }
        if (StrUtil.isNotBlank(conf.getStr("postSql"))) {
            writerParam.put("postSql", List.of(conf.getStr("postSql")));
        }
        return writerParam;
    }

    /**
     * Doris/SR Stream Load 写出参数。
     * <p>loadUrl/feLoadUrl 优先 conf，其次 {@code LH_DORIS_FE_HTTP} / {@code LH_SR_FE_HTTP}（Vault 注入）。</p>
     */
    private static Map<String, Object> buildStreamLoadWriterParam(
            String writerType, JSONObject conf, ColumnLists cols, String writerTable) {
        boolean doris = "doriswriter".equalsIgnoreCase(writerType);
        Map<String, Object> p = new LinkedHashMap<>();
        String feHttp = firstNonBlank(
                conf.getStr("loadUrl"), conf.getStr("feLoadUrl"), conf.getStr("fenodes"),
                doris ? "${LH_DORIS_FE_HTTP}" : "${LH_SR_FE_HTTP}",
                "${LH_FE_HTTP}");
        List<String> loadUrls = new ArrayList<>();
        for (String part : feHttp.split("[,;\\s]+")) {
            if (StrUtil.isNotBlank(part) && !part.contains("${")) {
                loadUrls.add(part.trim());
            } else if (StrUtil.isNotBlank(part)) {
                loadUrls.add(part.trim());
            }
        }
        if (loadUrls.isEmpty()) {
            loadUrls.add(doris ? "${LH_DORIS_FE_HTTP}" : "${LH_SR_FE_HTTP}");
        }
        if (doris) {
            p.put("loadUrl", loadUrls);
            p.put("feLoadUrl", loadUrls);
        } else {
            p.put("loadUrl", loadUrls);
        }
        p.put("username", "${LH_WRITER_JDBC_USER}");
        p.put("password", "${LH_WRITER_JDBC_PASSWORD}");
        p.put("jdbcUrl", "${LH_WRITER_JDBC_URL}");
        String db = firstNonBlank(conf.getStr("database"), conf.getStr("schema"), conf.getStr("lhDatabase"), "ods");
        String table = StrUtil.blankToDefault(writerTable, "lh_sync_target");
        String bare = table.contains(".") ? table.substring(table.lastIndexOf('.') + 1) : table;
        p.put("database", db);
        p.put("table", bare);
        p.put("column", cols.writer);
        Map<String, Object> loadProps = new LinkedHashMap<>();
        String fmt = firstNonBlank(conf.getStr("format"), conf.getStr("loadFormat"), "json");
        loadProps.put("format", fmt);
        if ("json".equalsIgnoreCase(fmt)) {
            loadProps.put("strip_outer_array", "true");
        }
        if (StrUtil.isNotBlank(conf.getStr("columnSeparator"))) {
            loadProps.put("column_separator", conf.getStr("columnSeparator"));
        }
        p.put("loadProps", loadProps);
        p.put("maxBatchRows", conf.getInt("maxBatchRows", 500000));
        p.put("maxBatchSize", conf.getLong("maxBatchSize", 104857600L));
        return p;
    }

    private static List<Map<String, Object>> toEsColumns(List<String> names) {
        List<Map<String, Object>> cols = new ArrayList<>();
        if (names == null || names.isEmpty() || (names.size() == 1 && "*".equals(names.get(0)))) {
            cols.add(Map.of("name", "id", "type", "id"));
            return cols;
        }
        for (String n : names) {
            cols.add(Map.of("name", n, "type", "text"));
        }
        return cols;
    }

    private static List<Map<String, Object>> toMongoColumns(List<String> names) {
        List<Map<String, Object>> cols = new ArrayList<>();
        if (names == null || names.isEmpty() || (names.size() == 1 && "*".equals(names.get(0)))) {
            cols.add(Map.of("name", "_id", "type", "string"));
            return cols;
        }
        for (String n : names) {
            cols.add(Map.of("name", n, "type", "string"));
        }
        return cols;
    }

    /** DataX hbasereader：首列多为 rowkey，其余 cf:qualifier */
    private static List<Map<String, Object>> toHbaseColumns(List<String> names, String columnFamily) {
        String cf = StrUtil.blankToDefault(columnFamily, "cf");
        List<Map<String, Object>> cols = new ArrayList<>();
        if (names == null || names.isEmpty() || (names.size() == 1 && "*".equals(names.get(0)))) {
            cols.add(Map.of("name", "rowkey", "type", "string"));
            cols.add(Map.of("name", cf + ":q1", "type", "string"));
            return cols;
        }
        int i = 0;
        for (String n : names) {
            Map<String, Object> c = new LinkedHashMap<>();
            if (i == 0 && ("rowkey".equalsIgnoreCase(n) || "id".equalsIgnoreCase(n) || "_id".equals(n))) {
                c.put("name", "rowkey");
            } else if (n.contains(":")) {
                c.put("name", n);
            } else {
                c.put("name", cf + ":" + n);
            }
            c.put("type", "string");
            cols.add(c);
            i++;
        }
        return cols;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String guessReader(String dsType, String nodeType) {
        String t = StrUtil.blankToDefault(dsType, "").toLowerCase(Locale.ROOT);
        if ("source_mongo".equals(nodeType) || t.contains("mongo")) {
            return "mongodbreader";
        }
        if ("source_hbase".equals(nodeType) || t.contains("hbase")) {
            return "hbasereader";
        }
        if ("source_api".equals(nodeType) || t.contains("http") || t.contains("api")) {
            return "httpreader";
        }
        if ("source_file".equals(nodeType)) {
            if (t.contains("hdfs")) {
                return "hdfsreader";
            }
            if (t.contains("s3") || t.contains("minio") || t.contains("oss")) {
                return "ossreader";
            }
            if (t.contains("ftp") || t.contains("sftp") || "file".equals(t)) {
                return "ftpreader";
            }
            return "txtfilereader";
        }
        if (t.contains("mysql") || t.contains("mariadb") || t.contains("tidb")) {
            return "mysqlreader";
        }
        if (t.contains("postgres") || "pg".equals(t) || t.contains("greenplum")) {
            return "postgresqlreader";
        }
        if (t.contains("oracle")) {
            return "oraclereader";
        }
        if (t.contains("sqlserver") || t.contains("mssql") || t.contains("azuresql")) {
            return "sqlserverreader";
        }
        if (t.contains("clickhouse") || "ck".equals(t)) {
            return "clickhousereader";
        }
        if (t.contains("doris") || t.contains("starrocks")) {
            // 官方插件名因发行版而异；JDBC 族用 mysql 协议兼容口
            return "mysqlreader";
        }
        if (t.contains("hive")) {
            return "hdfsreader";
        }
        if (t.contains("ftp") || t.contains("sftp")) {
            return "ftpreader";
        }
        if (t.contains("hdfs")) {
            return "hdfsreader";
        }
        if (t.contains("s3") || t.contains("minio") || t.contains("oss")) {
            return "ossreader";
        }
        return "mysqlreader";
    }

    private static String guessWriter(String dsTypeOrNode, String nodeType) {
        String t = StrUtil.blankToDefault(dsTypeOrNode, "").toLowerCase(Locale.ROOT);
        if ("sink_search".equals(nodeType) || t.contains("elastic") || t.contains("opensearch")) {
            return "elasticsearchwriter";
        }
        if ("sink_ftp".equals(nodeType) || t.contains("ftp") || t.contains("sftp") || "file".equals(t)) {
            return "ftpwriter";
        }
        if ("sink_object".equals(nodeType)) {
            if (t.contains("hdfs")) {
                return "hdfswriter";
            }
            return "osswriter";
        }
        if ("sink_ck".equals(nodeType) || t.contains("clickhouse") || "ck".equals(t)) {
            return "clickhousewriter";
        }
        if ("sink_iceberg".equals(nodeType) || t.contains("hdfs")) {
            return "hdfswriter";
        }
        if (t.contains("postgres") || "pg".equals(t)) {
            return "postgresqlwriter";
        }
        if (t.contains("oracle")) {
            return "oraclewriter";
        }
        if (t.contains("sqlserver") || t.contains("mssql") || t.contains("azuresql")) {
            return "sqlserverwriter";
        }
        if (t.contains("doris")) {
            return "doriswriter";
        }
        if (t.contains("starrocks")) {
            return "starrockswriter";
        }
        if (t.contains("mysql") || t.contains("mariadb") || t.contains("tidb")) {
            return "mysqlwriter";
        }
        if (t.contains("s3") || t.contains("minio") || t.contains("oss")) {
            return "osswriter";
        }
        if ("sink_rdb".equals(nodeType)) {
            // 未声明方言时默认 mysql；发布侧应注入 lhDsType / writerDsType
            return "mysqlwriter";
        }
        // 禁止未知 sink 静默落到 mysqlwriter：对象/搜索已上面覆盖
        if (nodeType != null && nodeType.startsWith("sink_")) {
            return "mysqlwriter";
        }
        return "mysqlwriter";
    }

    @SuppressWarnings("unchecked")
    private static String extractPluginName(Map<String, Object> root, String side) {
        try {
            Map<String, Object> job = (Map<String, Object>) root.get("job");
            List<Map<String, Object>> content = (List<Map<String, Object>>) job.get("content");
            Map<String, Object> plugin = (Map<String, Object>) content.get(0).get(side);
            return plugin == null ? null : String.valueOf(plugin.get("name"));
        } catch (Exception e) {
            return null;
        }
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
