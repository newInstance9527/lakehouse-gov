package vip.xiaonuo.lh.modular.etl.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vip.xiaonuo.lh.core.engine.FlinkSourceSqlCompiler;
import vip.xiaonuo.lh.core.engine.LhStagingTables;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;
import vip.xiaonuo.lh.modular.datasource.support.LhJdbcConnections;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 批处理链中间表：预建 / 存在预检 / 成功后清理。
 * <p>表名默认 {@code lh_ods_*} / {@code lh_clean_*}，可由 conf {@code landingTable}/{@code cleanTable} 覆盖。</p>
 */
public final class LhStagingDdlEnsured {

    private static final Logger log = LoggerFactory.getLogger(LhStagingDdlEnsured.class);

    private LhStagingDdlEnsured() {
    }

    /** conf.stagingAutoCreate 默认 true */
    public static boolean isAutoCreate(JSONObject conf) {
        if (conf == null) {
            return true;
        }
        if (conf.containsKey("stagingAutoCreate")) {
            Object v = conf.get("stagingAutoCreate");
            if (v instanceof Boolean b) {
                return b;
            }
            String s = String.valueOf(v).trim();
            if ("false".equalsIgnoreCase(s) || "0".equals(s) || "off".equalsIgnoreCase(s)) {
                return false;
            }
        }
        return true;
    }

    /** none | truncate | drop */
    public static String cleanupMode(JSONObject conf) {
        if (conf == null) {
            return "none";
        }
        String m = StrUtil.blankToDefault(conf.getStr("stagingCleanup"), "none").trim().toLowerCase(Locale.ROOT);
        if ("truncate".equals(m) || "drop".equals(m)) {
            return m;
        }
        return "none";
    }

    /**
     * 解析 ODS / 清洗中间表（可带库前缀）。
     *
     * @param database     默认库
     * @param sourceBare   源表裸名
     * @param conf         节点 conf（source 优先；clean 可带 cleanTable）
     * @param upstreamLand 上游已解析落地表（clean 推导用）
     */
    public static ResolvedTables resolveTables(String database, String sourceBare, JSONObject conf,
                                               String upstreamLand) {
        String bare = LhStagingTables.bareTable(sourceBare);
        if (StrUtil.isBlank(bare) && StrUtil.isNotBlank(upstreamLand)) {
            bare = LhStagingTables.stripStagingPrefix(upstreamLand);
        }
        String landOverride = conf == null ? null : firstNonBlank(
                conf.getStr("landingTable"), conf.getStr("_lhLandingTable"));
        String cleanOverride = conf == null ? null : firstNonBlank(
                conf.getStr("cleanTable"), conf.getStr("_lhCleanTable"));

        String landOnly;
        if (StrUtil.isNotBlank(landOverride)) {
            landOnly = LhStagingTables.bareTable(landOverride);
        } else {
            landOnly = LhStagingTables.odsTable(bare);
        }
        String landQualified = qualifyPrefer(landOverride, database, landOnly);

        String cleanOnly;
        if (StrUtil.isNotBlank(cleanOverride)) {
            cleanOnly = LhStagingTables.bareTable(cleanOverride);
        } else if (StrUtil.isNotBlank(upstreamLand)) {
            cleanOnly = LhStagingTables.cleanTableFromUpstream(upstreamLand);
        } else {
            cleanOnly = LhStagingTables.cleanTable(bare);
        }
        String cleanQualified = qualifyPrefer(cleanOverride, database, cleanOnly);

        return new ResolvedTables(landQualified, cleanQualified,
                LhStagingTables.bareTable(landQualified), LhStagingTables.bareTable(cleanQualified));
    }

    public static EnsureResult ensureOrCheck(
            LhDatasource ds, String jdbcUrl, String user, String password,
            String database, String odsTableOnly, String cleanTableOnly,
            List<?> colsRaw, boolean autoCreate) {
        List<FlinkSourceSqlCompiler.Column> cols = FlinkSourceSqlCompiler.coerceColumns(colsRaw);
        EnsureResult r = new EnsureResult();
        r.odsTable = odsTableOnly;
        r.cleanTable = cleanTableOnly;
        r.autoCreate = autoCreate;
        if (ds == null || StrUtil.isBlank(jdbcUrl)) {
            r.ok = false;
            r.message = "缺少数据源或 JDBC URL，无法处理中间表";
            return r;
        }
        String type = StrUtil.blankToDefault(ds.getType(), "").toLowerCase(Locale.ROOT);
        if (!(type.contains("mysql") || type.contains("mariadb") || type.contains("tidb"))) {
            r.ok = true;
            r.skipped = true;
            r.message = "非 MySQL 族，跳过 staging DDL";
            return r;
        }
        String ods = LhStagingTables.bareTable(odsTableOnly);
        String clean = LhStagingTables.bareTable(cleanTableOnly);
        if (StrUtil.isBlank(ods) || StrUtil.isBlank(clean)) {
            r.ok = false;
            r.message = "中间表名为空";
            return r;
        }
        r.ddlHint = buildDdlHint(database, ods, clean, cols);
        LhJdbcConnections.ensureDriver(ds.getType());
        try (Connection c = LhJdbcConnections.open(ds.getType(), jdbcUrl, user, password)) {
            boolean odsExists = tableExists(c, database, ods);
            boolean cleanExists = tableExists(c, database, clean);
            r.odsExists = odsExists;
            r.cleanExists = cleanExists;
            if (autoCreate) {
                if (cols.isEmpty()) {
                    r.ok = false;
                    r.message = "无列元数据，无法自动建中间表";
                    return r;
                }
                try (Statement st = c.createStatement()) {
                    st.execute(mysqlCreate(database, ods, cols));
                    st.execute(mysqlCreate(database, clean, cols));
                }
                r.ok = true;
                r.created = true;
                r.message = "已确保中间表存在: " + ods + ", " + clean;
                log.info("staging DDL ok db={} ods={} clean={}", database, ods, clean);
                return r;
            }
            // 预建关闭：表必须已存在
            if (odsExists && cleanExists) {
                r.ok = true;
                r.message = "中间表已存在（未自动创建）";
                return r;
            }
            r.ok = false;
            List<String> missing = new ArrayList<>();
            if (!odsExists) {
                missing.add(qualify(database, ods));
            }
            if (!cleanExists) {
                missing.add(qualify(database, clean));
            }
            r.message = "stagingAutoCreate=false 且中间表不存在: " + String.join(", ", missing)
                    + "；请 DBA 预建或开启自动创建。DDL 参考见 ddlHint";
            return r;
        } catch (Exception e) {
            r.ok = false;
            r.message = "中间表处理失败: " + e.getMessage();
            log.warn("staging DDL/check fail: {}", e.getMessage());
            return r;
        }
    }

    /** 兼容旧调用：始终尝试自动建表（soft） */
    public static void ensureMysqlFamily(
            LhDatasource ds, String jdbcUrl, String user, String password,
            String database, String tableOnly, List<?> colsRaw) {
        String ods = LhStagingTables.odsTable(tableOnly);
        String clean = LhStagingTables.cleanTable(tableOnly);
        EnsureResult r = ensureOrCheck(ds, jdbcUrl, user, password, database, ods, clean, colsRaw, true);
        if (!r.ok) {
            log.warn("staging DDL soft-fail: {}", r.message);
        }
    }

    public static EnsureResult ensureMysqlFamilyDetailed(
            LhDatasource ds, String jdbcUrl, String user, String password,
            String database, String odsTableOnly, String cleanTableOnly,
            List<?> colsRaw, boolean autoCreate) {
        return ensureOrCheck(ds, jdbcUrl, user, password, database, odsTableOnly, cleanTableOnly, colsRaw, autoCreate);
    }

    public static void cleanupMysqlFamily(
            LhDatasource ds, String jdbcUrl, String user, String password,
            String database, String odsTableOnly, String cleanTableOnly, String mode) {
        String m = StrUtil.blankToDefault(mode, "none").toLowerCase(Locale.ROOT);
        if ("none".equals(m) || ds == null || StrUtil.isBlank(jdbcUrl)) {
            return;
        }
        String type = StrUtil.blankToDefault(ds.getType(), "").toLowerCase(Locale.ROOT);
        if (!(type.contains("mysql") || type.contains("mariadb") || type.contains("tidb"))) {
            return;
        }
        String ods = LhStagingTables.bareTable(odsTableOnly);
        String clean = LhStagingTables.bareTable(cleanTableOnly);
        LhJdbcConnections.ensureDriver(ds.getType());
        try (Connection c = LhJdbcConnections.open(ds.getType(), jdbcUrl, user, password);
             Statement st = c.createStatement()) {
            if ("drop".equals(m)) {
                st.execute("DROP TABLE IF EXISTS " + tick(database, ods));
                st.execute("DROP TABLE IF EXISTS " + tick(database, clean));
                log.info("staging cleanup DROP db={} ods={} clean={}", database, ods, clean);
            } else if ("truncate".equals(m)) {
                if (tableExists(c, database, ods)) {
                    st.execute("TRUNCATE TABLE " + tick(database, ods));
                }
                if (tableExists(c, database, clean)) {
                    st.execute("TRUNCATE TABLE " + tick(database, clean));
                }
                log.info("staging cleanup TRUNCATE db={} ods={} clean={}", database, ods, clean);
            }
        } catch (Exception e) {
            log.warn("staging cleanup soft-fail: {}", e.getMessage());
        }
    }

    /**
     * DS 尾部 SHELL：curl 门户 {@code POST /lh/etl/staging/cleanup}（与质量门禁同口径，凭证在服务端）。
     */
    public static String cleanupShell(String dsId, String database, String odsBare, String cleanBare, String mode) {
        String m = StrUtil.blankToDefault(mode, "none").toLowerCase(Locale.ROOT);
        if ("none".equals(m)) {
            return "#!/bin/bash\necho \"[lh] staging cleanup skipped\"\nexit 0\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("#!/bin/bash\nset -euo pipefail\n");
        sb.append("MODE='").append(m.replace("'", "")).append("'\n");
        sb.append("DS_ID='").append(StrUtil.blankToDefault(dsId, "").replace("'", "")).append("'\n");
        sb.append("DB='").append(StrUtil.blankToDefault(database, "").replace("'", "")).append("'\n");
        sb.append("ODS='").append(LhStagingTables.bareTable(odsBare).replace("'", "")).append("'\n");
        sb.append("CLEAN='").append(LhStagingTables.bareTable(cleanBare).replace("'", "")).append("'\n");
        sb.append("echo \"[lh-staging] cleanup mode=$MODE ds=$DS_ID ods=$ODS clean=$CLEAN\"\n");
        sb.append("GOV=\"${LH_GOV_URL:-}\"\n");
        sb.append("if [ -z \"$GOV\" ]; then echo '[lh-staging] LH_GOV_URL empty — skip cleanup'; exit 0; fi\n");
        sb.append("GOV=\"${GOV%/}\"\n");
        sb.append("BODY=$(printf '{\"dsId\":\"%s\",\"database\":\"%s\",\"odsTable\":\"%s\",\"cleanTable\":\"%s\",\"mode\":\"%s\"}' \\\n");
        sb.append("  \"$DS_ID\" \"$DB\" \"$ODS\" \"$CLEAN\" \"$MODE\")\n");
        sb.append("RESP=$(curl -sS -m 120 -X POST \"$GOV/lh/etl/staging/cleanup\" \\\n");
        sb.append("  -H 'Content-Type: application/json' \\\n");
        sb.append("  ${LH_GOV_TOKEN:+-H \"token: $LH_GOV_TOKEN\"} \\\n");
        sb.append("  -d \"$BODY\" || true)\n");
        sb.append("echo \"[lh-staging] cleanup resp=${RESP:0:400}\"\n");
        sb.append("echo \"$RESP\" | grep -Eq '\"ok\"[[:space:]]*:[[:space:]]*true|\"code\"[[:space:]]*:[[:space:]]*200' \\\n");
        sb.append("  && echo '[lh-staging] cleanup ok' && exit 0\n");
        sb.append("echo '[lh-staging] cleanup soft-fail (non-blocking)'\nexit 0\n");
        return sb.toString();
    }

    public static String buildDdlHint(String database, String ods, String clean,
                                     List<FlinkSourceSqlCompiler.Column> cols) {
        if (cols == null || cols.isEmpty()) {
            return "-- DBA: CREATE TABLE IF NOT EXISTS " + qualify(database, ods)
                    + " (...align source columns...);\n"
                    + "-- DBA: CREATE TABLE IF NOT EXISTS " + qualify(database, clean) + " (...);";
        }
        return mysqlCreate(database, ods, cols) + ";\n" + mysqlCreate(database, clean, cols) + ";";
    }

    public static Map<String, Object> toMap(EnsureResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (r == null) {
            return m;
        }
        m.put("ok", r.ok);
        m.put("skipped", r.skipped);
        m.put("created", r.created);
        m.put("autoCreate", r.autoCreate);
        m.put("odsTable", r.odsTable);
        m.put("cleanTable", r.cleanTable);
        m.put("odsExists", r.odsExists);
        m.put("cleanExists", r.cleanExists);
        m.put("message", r.message);
        if (StrUtil.isNotBlank(r.ddlHint)) {
            m.put("ddlHint", r.ddlHint);
        }
        return m;
    }

    private static boolean tableExists(Connection c, String database, String table) throws Exception {
        DatabaseMetaData md = c.getMetaData();
        String catalog = StrUtil.blankToDefault(database, null);
        try (ResultSet rs = md.getTables(catalog, null, table, new String[]{"TABLE", "VIEW"})) {
            if (rs.next()) {
                return true;
            }
        }
        // 再试小写/原样
        try (ResultSet rs = md.getTables(catalog, null, table.toLowerCase(Locale.ROOT), new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    static String mysqlCreate(String database, String table, List<FlinkSourceSqlCompiler.Column> cols) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS ").append(tick(database, table)).append(" (\n");
        for (int i = 0; i < cols.size(); i++) {
            FlinkSourceSqlCompiler.Column col = cols.get(i);
            sb.append("  `").append(col.name().replace("`", "")).append("` ")
                    .append(toMysqlType(col.flinkType()));
            if (i < cols.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append(")");
        return sb.toString();
    }

    private static String toMysqlType(String flinkType) {
        String t = StrUtil.blankToDefault(flinkType, "STRING").toUpperCase(Locale.ROOT);
        if (t.startsWith("TIMESTAMP")) {
            return "DATETIME(3) NULL";
        }
        if (t.startsWith("DECIMAL") || t.startsWith("NUMERIC")) {
            return "DECIMAL(38,10) NULL";
        }
        return switch (t) {
            case "INT", "INTEGER" -> "INT NULL";
            case "BIGINT" -> "BIGINT NULL";
            case "DOUBLE", "FLOAT" -> "DOUBLE NULL";
            case "BOOLEAN" -> "TINYINT(1) NULL";
            case "DATE" -> "DATE NULL";
            default -> "TEXT NULL";
        };
    }

    private static String tick(String database, String table) {
        String t = "`" + table.replace("`", "") + "`";
        if (StrUtil.isBlank(database)) {
            return t;
        }
        return "`" + database.replace("`", "") + "`." + t;
    }

    private static String qualify(String database, String table) {
        return LhStagingTables.qualify(database, table);
    }

    private static String qualifyPrefer(String overrideQualifiedOrBare, String database, String bareOnly) {
        if (StrUtil.isNotBlank(overrideQualifiedOrBare) && overrideQualifiedOrBare.contains(".")) {
            return overrideQualifiedOrBare.replace("`", "");
        }
        return LhStagingTables.qualify(database, bareOnly);
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (StrUtil.isNotBlank(v) && !"null".equalsIgnoreCase(v.trim())) {
                return v.trim();
            }
        }
        return null;
    }

    public static final class ResolvedTables {
        public final String landingQualified;
        public final String cleanQualified;
        public final String landingBare;
        public final String cleanBare;

        public ResolvedTables(String landingQualified, String cleanQualified,
                              String landingBare, String cleanBare) {
            this.landingQualified = landingQualified;
            this.cleanQualified = cleanQualified;
            this.landingBare = landingBare;
            this.cleanBare = cleanBare;
        }
    }

    public static final class EnsureResult {
        public boolean ok;
        public boolean skipped;
        public boolean created;
        public boolean autoCreate = true;
        public Boolean odsExists;
        public Boolean cleanExists;
        public String odsTable;
        public String cleanTable;
        public String message;
        public String ddlHint;
    }
}
