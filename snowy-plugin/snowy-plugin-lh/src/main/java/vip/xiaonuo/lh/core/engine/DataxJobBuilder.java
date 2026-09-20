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

        // 读端：源节点用本节点 ds；汇节点用上游 / readerDsId
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

        Map<String, Object> writerParam = new LinkedHashMap<>();
        writerParam.put("username", "${LH_WRITER_JDBC_USER}");
        writerParam.put("password", "${LH_WRITER_JDBC_PASSWORD}");
        writerParam.put("column", cols.writer);
        // Writer：jdbcUrl 必须是字符串（Reader 才是数组）；写成数组会被当成字面量 URL → No suitable driver for ["jdbc:…
        writerParam.put("connection", List.of(Map.of(
                "table", List.of(StrUtil.blankToDefault(writerTable, "lh_sync_target")),
                "jdbcUrl", "${LH_WRITER_JDBC_URL}")));
        // DataX postgresqlwriter：禁止配置 writeMode（任意非 null 都会 ConfError；仅 insert）
        // 重复跑会撞主键：默认 DELETE 目标表（可用 conf.preSql / skipPreDelete 覆盖）
        if (!"postgresqlwriter".equalsIgnoreCase(writerType)) {
            String writeMode = StrUtil.blankToDefault(conf.getStr("writeMode"), "insert");
            writerParam.put("writeMode", writeMode);
        }
        if (StrUtil.isNotBlank(conf.getStr("preSql"))) {
            writerParam.put("preSql", List.of(conf.getStr("preSql")));
        } else if ("postgresqlwriter".equalsIgnoreCase(writerType)
                && !Boolean.TRUE.equals(conf.getBool("skipPreDelete"))) {
            String wt = StrUtil.blankToDefault(writerTable, "lh_sync_target");
            // 仅允许简单表名，防注入
            if (wt.matches("[A-Za-z0-9_\\.]+")) {
                writerParam.put("preSql", List.of("DELETE FROM " + wt));
            }
        }
        if (StrUtil.isNotBlank(conf.getStr("postSql"))) {
            writerParam.put("postSql", List.of(conf.getStr("postSql")));
        }

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
        sb.append("if [ -z \"${LH_READER_JDBC_URL}\" ]; then echo '[lh-datax] LH_READER_JDBC_URL empty (upstream ds missing)'; exit 2; fi\n");
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
        sb.append("if [ -z \"${LH_READER_JDBC_URL}\" ]; then echo '[lh-datax] LH_READER_JDBC_URL empty (upstream ds missing)'; exit 2; fi\n");
        sb.append("JOB_FILE=$(mktemp /tmp/lh-datax-${NODE_KEY}-XXXX.json)\n");
        sb.append("cat > \"$JOB_FILE\" <<'LH_DATAX_EOF'\n");
        sb.append(StrUtil.blankToDefault(jobJson, "{\"job\":{\"content\":[]}}")).append("\n");
        sb.append("LH_DATAX_EOF\n");
        appendExpandAndRun(sb);
        return sb.toString();
    }

    private static void appendExpandAndRun(StringBuilder sb) {
        sb.append("# 展开环境变量占位到 job 文件（URL/密码含 & 时禁止裸 sed）\n");
        LhEnvPlaceholderExpand.appendExpandFile(sb, "JOB_FILE",
                "LH_READER_JDBC_URL", "LH_READER_JDBC_USER", "LH_READER_JDBC_PASSWORD",
                "LH_WRITER_JDBC_URL", "LH_WRITER_JDBC_USER", "LH_WRITER_JDBC_PASSWORD");
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
        Object columnMap = conf.get("columnMap");
        if (columnMap instanceof String s && StrUtil.isNotBlank(s) && !"*".equals(s.trim())) {
            for (String p : s.split("[,;]")) {
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

    private static String guessReader(String dsType, String nodeType) {
        String t = StrUtil.blankToDefault(dsType, "").toLowerCase(Locale.ROOT);
        if (t.contains("mysql") || t.contains("mariadb") || t.contains("tidb")) {
            return "mysqlreader";
        }
        if (t.contains("postgres") || t.contains("pg") || t.contains("greenplum")) {
            return "postgresqlreader";
        }
        if (t.contains("oracle")) {
            return "oraclereader";
        }
        if (t.contains("sqlserver") || t.contains("mssql")) {
            return "sqlserverreader";
        }
        if (t.contains("clickhouse") || t.contains("ck")) {
            return "clickhousereader";
        }
        if (StrUtil.startWith(nodeType, "source_file")) {
            return "txtfilereader";
        }
        if (StrUtil.startWith(nodeType, "source_api")) {
            return "httpreader";
        }
        return "mysqlreader";
    }

    private static String guessWriter(String dsTypeOrNode, String nodeType) {
        String t = StrUtil.blankToDefault(dsTypeOrNode, "").toLowerCase(Locale.ROOT);
        if ("sink_ck".equals(nodeType) || t.contains("clickhouse") || t.contains("ck")) {
            return "clickhousewriter";
        }
        if ("sink_iceberg".equals(nodeType) || "sink_hive".equals(nodeType) || t.contains("hive")) {
            return "hdfswriter";
        }
        if (t.contains("postgres") || t.contains("pg")) {
            return "postgresqlwriter";
        }
        if (t.contains("oracle")) {
            return "oraclewriter";
        }
        if (t.contains("sqlserver") || t.contains("mssql")) {
            return "sqlserverwriter";
        }
        if (StrUtil.startWith(nodeType, "sink_") || t.contains("mysql")) {
            return "mysqlwriter";
        }
        return "mysqlwriter";
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
