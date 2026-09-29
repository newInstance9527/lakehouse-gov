package vip.xiaonuo.lh.modular.etl.support;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.engine.FlinkIcebergSinkSqlCompiler;
import vip.xiaonuo.lh.core.engine.GravitinoClient;
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;
import vip.xiaonuo.lh.modular.datasource.entity.LhDsTable;
import vip.xiaonuo.lh.modular.datasource.mapper.LhDatasourceMapper;
import vip.xiaonuo.lh.modular.datasource.mapper.LhDsTableMapper;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlDag;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlNode;
import vip.xiaonuo.lh.modular.query.support.LakeQueryAssetBinder;
import vip.xiaonuo.lh.modular.schemasync.entity.CbGravAssetRef;
import vip.xiaonuo.lh.modular.schemasync.mapper.CbGravAssetRefMapper;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sink 目标存在性：Iceberg/CK → Grav；RDB → 绑定数据源 JDBC / ig_ds_table（禁止假 catalog=jdbc）。
 */
@Component
public class IgEtlSinkTargetChecker {

    private static final Logger log = LoggerFactory.getLogger(IgEtlSinkTargetChecker.class);
    private static final String NOT_DELETE = "NOT_DELETE";

    @Resource
    private GravitinoClient gravitinoClient;
    @Resource
    private LhProperties lhProperties;
    @Resource
    private CbGravAssetRefMapper gravAssetRefMapper;
    @Resource
    private LakeQueryAssetBinder lakeQueryAssetBinder;
    @Resource
    private LhDatasourceMapper datasourceMapper;
    @Resource
    private LhDsTableMapper dsTableMapper;
    @Resource
    private LhVaultClient vaultClient;

    public List<Map<String, Object>> validateSink(IgEtlNode node) {
        List<Map<String, Object>> issues = new ArrayList<>();
        String type = node.getNodeType();
        if (!isAutoCreateSink(type)) {
            return issues;
        }
        JSONObject conf = parseConf(node.getConfJson());
        String mode = StrUtil.blankToDefault(conf.getStr("autoCreate"), "off");
        TargetRef ref = resolveTarget(type, conf);

        String saRole = firstNonBlank(conf.getStr("saRole"), conf.getStr("writeSa"), conf.getStr("jobSa"));
        if (StrUtil.isBlank(saRole)) {
            issues.add(issue(node.getNodeKey(), "error",
                    type + " 须配置 saRole（作业写账号，如 job.trade.ods_writer）"));
        } else if (!saRole.toLowerCase(Locale.ROOT).startsWith("job.")) {
            issues.add(issue(node.getNodeKey(), "warn",
                    type + " saRole 建议按附录 B：job.{domain}.{layer}_{role}，当前=" + saRole));
        }

        if (ref == null || StrUtil.isBlank(ref.table)) {
            return issues;
        }
        if ("sink_rdb".equals(type) && StrUtil.isBlank(conf.getStr("dsId"))) {
            issues.add(issue(node.getNodeKey(), "error",
                    "sink_rdb 须绑定 dsId，才能按数据源 JDBC 校验目标表（勿依赖 Grav jdbc.default）"));
        }

        ExistResult exist = checkExists(type, conf, ref);
        if ("if_not_exists".equals(mode)) {
            if (!exist.exists && exist.degraded) {
                issues.add(issue(node.getNodeKey(), "warn",
                        type + " 无法确认是否存在；发布将登记建表意图：" + ref.fqn()
                                + "（" + exist.message + "）"));
            }
            // 已存在 / 将建表：通过或计划项不写入 issues
        } else if (exist.exists) {
            // 已确认存在：通过项不展示
        } else if (exist.degraded) {
            String level = "fail_if_missing".equals(mode) ? "error" : "warn";
            issues.add(issue(node.getNodeKey(), level,
                    type + " 无法确认目标存在：" + ref.fqn() + " — " + exist.message));
        } else {
            issues.add(issue(node.getNodeKey(), "error",
                    type + " 目标表不存在：" + ref.fqn()
                            + "（autoCreate=" + mode + "；可改为 if_not_exists 或先建表"
                            + ("sink_rdb".equals(type)
                            ? "；PG 请用 public.表名 或填写 schema"
                            : "")
                            + "）"));
        }
        return issues;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> applyOnDeploy(IgEtlDag dag, List<IgEtlNode> nodes, Map<String, Object> workflow) {
        int checked = 0;
        int missing = 0;
        int created = 0;
        int createFail = 0;
        int skipped = 0;
        List<String> notes = new ArrayList<>();
        List<Map<String, Object>> plans = new ArrayList<>();

        Map<String, Map<String, Object>> taskByKey = new LinkedHashMap<>();
        Object tasksObj = workflow != null ? workflow.get("tasks") : null;
        if (tasksObj instanceof List<?> tasks) {
            for (Object t : tasks) {
                if (t instanceof Map<?, ?> m && m.get("nodeKey") != null) {
                    taskByKey.put(String.valueOf(m.get("nodeKey")), (Map<String, Object>) m);
                }
            }
        }

        for (IgEtlNode n : nodes) {
            if (!isAutoCreateSink(n.getNodeType())) {
                continue;
            }
            checked++;
            JSONObject conf = parseConf(n.getConfJson());
            String mode = StrUtil.blankToDefault(conf.getStr("autoCreate"), "off");
            TargetRef ref = resolveTarget(n.getNodeType(), conf);
            if (ref == null || StrUtil.isBlank(ref.table)) {
                skipped++;
                continue;
            }
            ExistResult exist = checkExists(n.getNodeType(), conf, ref);
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("nodeKey", n.getNodeKey());
            plan.put("nodeType", n.getNodeType());
            plan.put("autoCreate", mode);
            plan.put("target", ref.fqn());
            plan.put("exists", exist.exists);
            plan.put("existSource", exist.source);
            plan.put("saRole", firstNonBlank(conf.getStr("saRole"), conf.getStr("writeSa")));
            plan.put("registerAfterCreate", conf.getBool("registerAfterCreate", true));

            if ("if_not_exists".equals(mode) && !exist.exists) {
                missing++;
                Map<String, Object> createResult = tryCreate(n.getNodeType(), conf, ref);
                plan.put("create", createResult);
                if (Boolean.TRUE.equals(createResult.get("ok"))) {
                    created++;
                } else {
                    createFail++;
                    notes.add(n.getNodeKey() + ": " + createResult.get("message"));
                }
                Map<String, Object> task = taskByKey.get(n.getNodeKey());
                if (task != null) {
                    Object tp = task.get("taskParams");
                    Map<String, Object> params = tp instanceof Map<?, ?> m
                            ? (Map<String, Object>) m
                            : new LinkedHashMap<>();
                    params.put("lhAutoCreate", mode);
                    params.put("lhCreateTarget", ref.fqn());
                    params.put("lhCreatePlan", createResult);
                    params.put("lhSaRole", plan.get("saRole"));
                    task.put("taskParams", params);
                }
            } else if (!exist.exists && ("off".equals(mode) || "fail_if_missing".equals(mode))) {
                notes.add(n.getNodeKey() + " 目标仍不存在: " + ref.fqn());
            }
            plans.add(plan);
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("dagId", dag.getId());
        r.put("checked", checked);
        r.put("missing", missing);
        r.put("created", created);
        r.put("createFail", createFail);
        r.put("skipped", skipped);
        r.put("plans", plans);
        if (!notes.isEmpty()) {
            r.put("notes", notes.size() > 8 ? notes.subList(0, 8) : notes);
        }
        if (workflow != null) {
            workflow.put("sinkTargetPlans", plans);
        }
        return r;
    }

    private Map<String, Object> tryCreate(String sinkType, JSONObject conf, TargetRef ref) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("target", ref.fqn());
        r.put("sinkType", sinkType);
        if (!"sink_iceberg".equals(sinkType)) {
            r.put("ok", true);
            r.put("deferred", true);
            r.put("message", "非 Iceberg：建表意图已注入 DS 任务，由引擎执行 DDL");
            r.put("ddlHint", buildSimpleDdlHint(sinkType, conf, ref));
            return r;
        }
        try {
            List<Map<String, Object>> columns = columnsFromConf(conf);
            if (columns.isEmpty()) {
                columns = List.of(
                        Map.of("name", "id", "type", "long", "nullable", false, "comment", "pk"),
                        Map.of("name", "dt", "type", "string", "nullable", true, "comment", "partition"));
            }
            gravitinoClient.createTable(ref.metalake, ref.catalog, ref.schema, ref.table, columns,
                    conf.getStr("comment"));
            r.put("ok", true);
            r.put("via", "gravitino");
            r.put("message", "Grav CREATE TABLE 已提交");
            if (conf.getBool("registerAfterCreate", true)) {
                upsertGravRef(ref);
                r.put("registered", true);
            }
        } catch (Exception e) {
            log.warn("Grav createTable soft-fail {}: {}", ref.fqn(), e.getMessage());
            r.put("ok", false);
            r.put("deferred", true);
            r.put("message", e.getMessage());
            r.put("ddlHint", buildSimpleDdlHint(sinkType, conf, ref));
        }
        return r;
    }

    private void upsertGravRef(TargetRef ref) {
        try {
            CbGravAssetRef existing = gravAssetRefMapper.selectOne(new QueryWrapper<CbGravAssetRef>().lambda()
                    .eq(CbGravAssetRef::getGravMetalake, ref.metalake)
                    .eq(CbGravAssetRef::getGravCatalog, ref.catalog)
                    .eq(CbGravAssetRef::getGravSchema, ref.schema)
                    .eq(CbGravAssetRef::getGravTable, ref.table)
                    .eq(CbGravAssetRef::getDeleteFlag, NOT_DELETE)
                    .last("LIMIT 1"));
            if (existing == null) {
                existing = new CbGravAssetRef();
                existing.setId(IdUtil.getSnowflakeNextIdStr());
                existing.setRevision(1);
                existing.setStatus("active");
                existing.setWs("default");
                existing.setDeleteFlag(NOT_DELETE);
                existing.setGravMetalake(ref.metalake);
                existing.setGravCatalog(ref.catalog);
                existing.setGravSchema(ref.schema);
                existing.setGravTable(ref.table);
                gravAssetRefMapper.insert(existing);
            }
            lakeQueryAssetBinder.ensure(existing);
        } catch (Exception e) {
            log.warn("register cb_grav_asset_ref soft-fail: {}", e.getMessage());
        }
    }

    private ExistResult checkExists(String sinkType, JSONObject conf, TargetRef ref) {
        if ("sink_rdb".equals(sinkType)) {
            return checkExistsRdb(conf, ref);
        }
        try {
            Long cnt = gravAssetRefMapper.selectCount(new QueryWrapper<CbGravAssetRef>().lambda()
                    .eq(CbGravAssetRef::getGravCatalog, ref.catalog)
                    .eq(CbGravAssetRef::getGravSchema, ref.schema)
                    .eq(CbGravAssetRef::getGravTable, ref.table)
                    .eq(CbGravAssetRef::getDeleteFlag, NOT_DELETE));
            if (cnt != null && cnt > 0) {
                return ExistResult.found("cb_grav_asset_ref");
            }
        } catch (Exception e) {
            log.debug("cb_grav_asset_ref lookup skip: {}", e.getMessage());
        }
        try {
            gravitinoClient.loadTable(ref.metalake, ref.catalog, ref.schema, ref.table);
            return ExistResult.found("gravitino");
        } catch (Exception e) {
            String msg = StrUtil.blankToDefault(e.getMessage(), "");
            String lower = msg.toLowerCase(Locale.ROOT);
            if (lower.contains("404") || lower.contains("not found") || lower.contains("does not exist")
                    || lower.contains("no such")) {
                return ExistResult.missing(msg);
            }
            return ExistResult.degraded(msg);
        }
    }

    /**
     * RDB：ig_ds_table 命中或 JDBC DatabaseMetaData；默认 schema=PG public / MySQL=库名。
     */
    private ExistResult checkExistsRdb(JSONObject conf, TargetRef ref) {
        String dsId = conf.getStr("dsId");
        if (StrUtil.isBlank(dsId)) {
            return ExistResult.degraded("缺少 dsId，无法 JDBC 校验");
        }
        LhDatasource ds = datasourceMapper.selectById(dsId);
        if (ds == null) {
            return ExistResult.missing("数据源不存在: " + dsId);
        }
        fillRdbSchemaDefault(ref, ds);

        // 1) 门户清单：支持 table / schema.table
        try {
            List<String> candidates = List.of(
                    ref.table,
                    ref.schema + "." + ref.table,
                    "public." + ref.table);
            Long cnt = dsTableMapper.selectCount(new QueryWrapper<LhDsTable>().lambda()
                    .eq(LhDsTable::getDsId, dsId)
                    .in(LhDsTable::getTableName, candidates));
            if (cnt != null && cnt > 0) {
                return ExistResult.found("ig_ds_table");
            }
        } catch (Exception e) {
            log.debug("ig_ds_table lookup soft-fail: {}", e.getMessage());
        }

        // 2) JDBC 真查
        if (StrUtil.isBlank(ds.getVaultPath())) {
            return ExistResult.degraded("数据源无 vaultPath");
        }
        try {
            Map<String, Object> secret = vaultClient.readOrEmpty(ds.getVaultPath());
            String url = firstNonBlank(str(secret.get("jdbcUrl")), null);
            String user = firstNonBlank(str(secret.get("username")), str(secret.get("user")), null);
            String pwd = firstNonBlank(str(secret.get("password")), "");
            if (StrUtil.isBlank(url)) {
                return ExistResult.degraded("Vault 无 jdbcUrl");
            }
            try (Connection conn = DriverManager.getConnection(url, user, pwd)) {
                DatabaseMetaData md = conn.getMetaData();
                // PostgreSQL：schema=public；MySQL：schema 常为空、catalog=库名
                String catalog = null;
                String schemaPattern = ref.schema;
                String type = StrUtil.blankToDefault(ds.getType(), "").toLowerCase(Locale.ROOT);
                if (type.contains("mysql") || type.contains("doris") || type.contains("mariadb")) {
                    catalog = firstNonBlank(ds.getDatabaseName(), ref.schema);
                    schemaPattern = null;
                }
                try (ResultSet rs = md.getTables(catalog, schemaPattern, ref.table,
                        new String[]{"TABLE", "PARTITIONED TABLE", "VIEW"})) {
                    if (rs.next()) {
                        return ExistResult.found("jdbc");
                    }
                }
                // PG 再试 public
                if (isPostgres(type) && !"public".equalsIgnoreCase(ref.schema)) {
                    try (ResultSet rs = md.getTables(null, "public", ref.table,
                            new String[]{"TABLE", "VIEW"})) {
                        if (rs.next()) {
                            ref.schema = "public";
                            return ExistResult.found("jdbc");
                        }
                    }
                }
            }
            return ExistResult.missing("JDBC 未找到 " + ref.schema + "." + ref.table);
        } catch (Exception e) {
            log.warn("RDB JDBC exists check soft-fail ds={}: {}", dsId, e.getMessage());
            return ExistResult.degraded(e.getMessage());
        }
    }

    /**
     * 解析 sink 目标坐标（校验 / 发布 / 试跑结果预览共用）。
     */
    public TargetRef resolveSinkTarget(String sinkType, JSONObject conf) {
        return resolveTarget(sinkType, conf);
    }

    private TargetRef resolveTarget(String sinkType, JSONObject conf) {
        String catalog;
        String schema;
        String table;
        if ("sink_iceberg".equals(sinkType)) {
            // 与 Flink 入湖一致：prod_catalog/hive → iceberg（autoCreate 只建 schema/表，不建 catalog）
            catalog = FlinkIcebergSinkSqlCompiler.lakeCatalog(conf);
            if (StrUtil.isBlank(catalog)) {
                catalog = firstNonBlank(lhProperties.getGravitino().getCatalog(), "iceberg");
            }
            schema = firstNonBlank(conf.getStr("database"), conf.getStr("schema"), "default");
            table = conf.getStr("table");
        } else if ("sink_ck".equals(sinkType)) {
            catalog = firstNonBlank(conf.getStr("catalog"), "clickhouse");
            schema = firstNonBlank(conf.getStr("database"), "default");
            table = conf.getStr("table");
        } else if ("sink_rdb".equals(sinkType)) {
            // 展示前缀：ds 名/编码，勿裸露雪花 dsId
            String dsId = conf.getStr("dsId");
            String dsLabel = firstNonBlank(conf.getStr("dsCode"), conf.getStr("dsName"));
            LhDatasource ds = null;
            if (StrUtil.isNotBlank(dsId)) {
                ds = datasourceMapper.selectById(dsId);
                if (ds != null) {
                    dsLabel = firstNonBlank(ds.getName(), ds.getDsCode(), dsLabel);
                }
            }
            catalog = firstNonBlank(dsLabel, "rdb");
            String raw = StrUtil.blankToDefault(conf.getStr("table"), "").trim();
            String schemaConf = firstNonBlank(conf.getStr("schema"), conf.getStr("tableSchema"));
            if (StrUtil.isNotBlank(raw) && raw.contains(".")) {
                String[] p = raw.split("\\.", 2);
                schema = p[0];
                table = p[1];
            } else {
                schema = schemaConf;
                table = raw;
            }
            // database 是 JDBC 库名，不是 PG schema；勿再把 database 当成 schema
            if (StrUtil.isBlank(schema)) {
                if (ds == null && StrUtil.isNotBlank(dsId)) {
                    ds = datasourceMapper.selectById(dsId);
                }
                if (ds != null) {
                    TargetRef tmp = new TargetRef();
                    tmp.schema = null;
                    tmp.table = table;
                    fillRdbSchemaDefault(tmp, ds);
                    schema = tmp.schema;
                }
            }
            if (StrUtil.isBlank(schema)) {
                schema = "public";
            }
        } else {
            return null;
        }
        if (StrUtil.isBlank(table)) {
            return null;
        }
        TargetRef r = new TargetRef();
        r.metalake = firstNonBlank(conf.getStr("metalake"), lhProperties.getGravitino().getMetalake(), "lakehouse");
        r.catalog = catalog;
        r.schema = schema;
        r.table = table.trim();
        r.rdb = "sink_rdb".equals(sinkType);
        return r;
    }

    private static void fillRdbSchemaDefault(TargetRef ref, LhDatasource ds) {
        if (ref == null || StrUtil.isNotBlank(ref.schema)) {
            return;
        }
        String type = StrUtil.blankToDefault(ds == null ? null : ds.getType(), "").toLowerCase(Locale.ROOT);
        if (isPostgres(type)) {
            ref.schema = "public";
        } else if (type.contains("mysql") || type.contains("doris") || type.contains("mariadb")
                || type.contains("clickhouse")) {
            ref.schema = StrUtil.blankToDefault(ds.getDatabaseName(), "public");
        } else if (type.contains("sqlserver")) {
            ref.schema = "dbo";
        } else if (type.contains("oracle")) {
            ref.schema = firstNonBlank(ds.getDatabaseName(), "PUBLIC");
        } else {
            ref.schema = "public";
        }
    }

    private static boolean isPostgres(String type) {
        return type != null && (type.contains("postgres") || "pg".equals(type));
    }

    private static List<Map<String, Object>> columnsFromConf(JSONObject conf) {
        List<Map<String, Object>> cols = new ArrayList<>();
        JSONArray maps = conf.getJSONArray("fieldMaps");
        if (maps == null) {
            maps = conf.getJSONArray("mapList");
        }
        if (maps != null) {
            for (int i = 0; i < maps.size(); i++) {
                JSONObject row = maps.getJSONObject(i);
                if (row == null) {
                    continue;
                }
                String name = firstNonBlank(row.getStr("dst"), row.getStr("name"), row.getStr("src"));
                if (StrUtil.isBlank(name)) {
                    continue;
                }
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("name", name);
                c.put("type", StrUtil.blankToDefault(row.getStr("type"), "string").toLowerCase(Locale.ROOT));
                c.put("nullable", true);
                c.put("comment", StrUtil.nullToEmpty(row.getStr("comment")));
                cols.add(c);
            }
        }
        return cols;
    }

    private static String buildSimpleDdlHint(String sinkType, JSONObject conf, TargetRef ref) {
        if ("sink_ck".equals(sinkType)) {
            return "CREATE TABLE IF NOT EXISTS " + ref.schema + "." + ref.table
                    + " (... ) ENGINE=" + StrUtil.blankToDefault(conf.getStr("engine"), "MergeTree");
        }
        if ("sink_rdb".equals(sinkType)) {
            return "CREATE TABLE IF NOT EXISTS " + ref.schema + "." + ref.table + " (...);";
        }
        return "CREATE TABLE IF NOT EXISTS " + ref.fqn() + " (...);";
    }

    private static boolean isAutoCreateSink(String type) {
        return "sink_iceberg".equals(type) || "sink_ck".equals(type) || "sink_rdb".equals(type);
    }

    private static Map<String, Object> issue(String ref, String level, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scope", "node");
        m.put("ref", ref);
        m.put("level", level);
        m.put("message", message);
        return m;
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

    private static String firstNonBlank(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (StrUtil.isNotBlank(v)) {
                return v;
            }
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    public static final class TargetRef {
        public String metalake;
        public String catalog;
        public String schema;
        public String table;
        public boolean rdb;

        public String fqn() {
            if (rdb) {
                return StrUtil.blankToDefault(catalog, "rdb") + "." + schema + "." + table;
            }
            return catalog + "." + schema + "." + table;
        }
    }

    static final class ExistResult {
        boolean exists;
        boolean degraded;
        String source;
        String message;

        static ExistResult found(String source) {
            ExistResult r = new ExistResult();
            r.exists = true;
            r.source = source;
            return r;
        }

        static ExistResult missing(String message) {
            ExistResult r = new ExistResult();
            r.exists = false;
            r.message = message;
            return r;
        }

        static ExistResult degraded(String message) {
            ExistResult r = new ExistResult();
            r.exists = false;
            r.degraded = true;
            r.message = message;
            return r;
        }
    }
}
