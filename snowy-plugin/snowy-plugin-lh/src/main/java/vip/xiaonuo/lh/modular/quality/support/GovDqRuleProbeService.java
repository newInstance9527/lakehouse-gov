package vip.xiaonuo.lh.modular.quality.support;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.engine.TrinoClient;
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.catalog.entity.GovAssetSourceLink;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetMapper;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetSourceLinkMapper;
import vip.xiaonuo.lh.modular.catalog.preview.PreviewAdapterSupport;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;
import vip.xiaonuo.lh.modular.datasource.mapper.LhDatasourceMapper;
import vip.xiaonuo.lh.modular.datasource.support.LhIcebergNamespaceNames;
import vip.xiaonuo.lh.modular.quality.entity.GovDqRule;
import vip.xiaonuo.lh.modular.schemasync.entity.CbGravAssetRef;
import vip.xiaonuo.lh.modular.schemasync.mapper.CbGravAssetRefMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 规则真探数：Trino（JOB 身份）优先；RDB 资产走 JDBC。
 */
@Component
public class GovDqRuleProbeService {

    private static final Logger log = LoggerFactory.getLogger(GovDqRuleProbeService.class);
    private static final String LINK_PRIMARY = "primary";
    private static final String NOT_DELETE = "NOT_DELETE";

    @Resource
    private TrinoClient trinoClient;
    @Resource
    private LhProperties lhProperties;
    @Resource
    private GovAssetMapper assetMapper;
    @Resource
    private GovAssetSourceLinkMapper linkMapper;
    @Resource
    private LhDatasourceMapper datasourceMapper;
    @Resource
    private CbGravAssetRefMapper gravAssetRefMapper;
    @Resource
    private LhVaultClient vaultClient;
    @Resource
    private GovDqStdCodeResolver stdCodeResolver;

    public static final class ProbeResult {
        public boolean pass;
        public long okRows;
        public long failRows;
        public BigDecimal okPct;
        public String message;
        public String engine;
        public String sql;
        public boolean degraded;
    }

    public ProbeResult probe(GovDqRule rule) {
        ProbeResult out = new ProbeResult();
        if (rule == null) {
            out.pass = false;
            out.message = "规则为空";
            out.degraded = true;
            return out;
        }
        Target target;
        try {
            target = resolveTarget(rule);
        } catch (Exception e) {
            out.pass = false;
            out.degraded = true;
            out.message = "解析探数目标失败: " + StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName());
            return out;
        }
        GovDqRuleSqlBuilder.Plan plan;
        try {
            plan = buildPlan(rule, target);
        } catch (Exception e) {
            out.pass = false;
            out.degraded = true;
            out.message = "生成探针 SQL 失败: " + StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName());
            return out;
        }
        out.sql = plan.sql;
        out.engine = target.engine;
        try {
            Map<String, Object> exec = "jdbc".equals(target.engine)
                    ? execJdbc(target, plan.sql)
                    : execTrino(target, plan.sql);
            if (Boolean.TRUE.equals(exec.get("degraded"))) {
                out.pass = false;
                out.degraded = true;
                out.message = "探数降级: " + StrUtil.blankToDefault(String.valueOf(exec.get("message")), "引擎不可用");
                return out;
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) exec.getOrDefault("rows", List.of());
            if (rows == null || rows.isEmpty()) {
                out.pass = false;
                out.degraded = true;
                out.message = "探数无结果行";
                return out;
            }
            Map<String, Object> row0 = rows.get(0);
            long total = firstLong(row0, "total_cnt", "total", "TOTAL_CNT", "cnt");
            long fail = firstLong(row0, "fail_cnt", "fail", "FAIL_CNT", "c");
            if (fail < 0) {
                fail = 0;
            }
            if (total < 0) {
                total = 0;
            }
            if (fail > total && total > 0) {
                // 查重包装时 fail=重复组数，可大于「应有唯一键数」但仍有意义；okRows 取 max(0,total-fail)
                // 保持 fail 原值，okPct 用 clamp
            }
            long ok = Math.max(0L, total - fail);
            out.okRows = ok;
            out.failRows = fail;
            out.pass = fail == 0;
            if (total <= 0) {
                out.okPct = new BigDecimal("100.00");
                out.pass = true; // 空表视为通过
                out.okRows = 0;
                out.failRows = 0;
            } else {
                BigDecimal pct = BigDecimal.valueOf(ok)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
                if (pct.compareTo(BigDecimal.ZERO) < 0) {
                    pct = BigDecimal.ZERO;
                }
                if (pct.compareTo(new BigDecimal("100")) > 0) {
                    pct = new BigDecimal("100.00");
                }
                out.okPct = pct;
            }
            out.message = (out.pass ? "探数通过" : "探数失败")
                    + " · " + target.engine
                    + " · " + plan.mode
                    + " · fail=" + out.failRows + "/" + Math.max(total, out.failRows)
                    + (StrUtil.isBlank(plan.hint) ? "" : (" · " + plan.hint));
            return out;
        } catch (Exception e) {
            log.warn("DQ probe fail rule={}: {}", rule.getId(), e.getMessage());
            out.pass = false;
            out.degraded = true;
            out.message = "探数异常: " + StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName());
            return out;
        }
    }

    private GovDqRuleSqlBuilder.Plan buildPlan(GovDqRule rule, Target target) {
        String expr = rule.getExprText();
        if (GovDqStdCodeResolver.isEnumRule(rule) && !looksLikeRowSql(expr)) {
            GovDqStdCodeResolver.Resolved std = stdCodeResolver.resolve(rule);
            if (std != null && std.itemCodes != null && !std.itemCodes.isEmpty()) {
                return GovDqRuleSqlBuilder.buildEnum(
                        rule.getFieldName(), std.itemCodes, target.qualifiedSql, target.dialect);
            }
            if (std != null && StrUtil.isNotBlank(std.codeSetId)
                    && (std.itemCodes == null || std.itemCodes.isEmpty())) {
                throw new IllegalArgumentException("码值集 " + std.codeSetId + " 无枚举项");
            }
        }
        return GovDqRuleSqlBuilder.build(
                rule.getRuleType(), rule.getRuleCode(), rule.getScope(),
                rule.getFieldName(), expr, target.qualifiedSql, target.dialect);
    }

    /** 显式行级/SQL 表达式（非单纯 codeSet= 标记）时走通用 builder。 */
    private static boolean looksLikeRowSql(String expr) {
        if (StrUtil.isBlank(expr)) {
            return false;
        }
        if (GovDqStdCodeResolver.parseCodeSetFromExpr(expr) != null) {
            String u = expr.toUpperCase(Locale.ROOT);
            return u.contains(" IN ") || u.startsWith("SELECT") || u.contains(" NOT ");
        }
        return true;
    }

    private Map<String, Object> execTrino(Target target, String sql) {
        TrinoClient.ExecuteOptions opts = TrinoClient.ExecuteOptions.defaults();
        opts.identity = TrinoClient.ExecuteOptions.Identity.JOB;
        opts.maxRows = 20;
        opts.catalog = StrUtil.blankToDefault(target.catalog, "iceberg");
        opts.schema = StrUtil.blankToDefault(target.schema, "default");
        opts.source = "lakehouse-dq";
        opts.timeoutMs = 120_000;
        return trinoClient.execute(sql, opts);
    }

    private Map<String, Object> execJdbc(Target target, String sql) throws Exception {
        Map<String, Object> secret = vaultClient.readOrEmpty(target.vaultPath);
        String url = firstStr(secret.get("jdbcUrl"));
        String user = firstStr(secret.get("username"), secret.get("user"));
        String pwd = firstStr(secret.get("password"));
        if (StrUtil.isBlank(url)) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("degraded", true);
            d.put("message", "Vault 无 jdbcUrl");
            d.put("rows", List.of());
            return d;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(url, user, StrUtil.nullToEmpty(pwd));
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            int cc = md.getColumnCount();
            List<String> cols = new ArrayList<>();
            for (int i = 1; i <= cc; i++) {
                cols.add(md.getColumnLabel(i));
            }
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= cc; i++) {
                    row.put(cols.get(i - 1), rs.getObject(i));
                }
                rows.add(row);
            }
        }
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("rows", rows);
        ok.put("rowCount", rows.size());
        return ok;
    }

    private Target resolveTarget(GovDqRule rule) {
        Target t = new Target();
        GovAsset asset = null;
        if (StrUtil.isNotBlank(rule.getAssetId())) {
            asset = assetMapper.selectById(rule.getAssetId());
        }
        GovAssetSourceLink primary = null;
        LhDatasource ds = null;
        if (asset != null) {
            primary = linkMapper.selectOne(new QueryWrapper<GovAssetSourceLink>().lambda()
                    .eq(GovAssetSourceLink::getAssetId, asset.getId())
                    .eq(GovAssetSourceLink::getLinkRole, LINK_PRIMARY)
                    .eq(GovAssetSourceLink::getDeleteFlag, NOT_DELETE)
                    .last("LIMIT 1"));
            if (primary != null && StrUtil.isNotBlank(primary.getDsId())) {
                ds = datasourceMapper.selectById(primary.getDsId());
            }
            if (ds == null && primary != null && StrUtil.isNotBlank(primary.getDsCode())) {
                ds = findDatasourceByIdOrCode(primary.getDsCode());
            }
        }

        String rawObject = StrUtil.blankToDefault(
                primary != null ? primary.getObjectName() : null,
                rule.getTableName());
        // 门户 FQN 常带 ds_<id|code>.schema.table；不得当作 Trino catalog
        PortalTableRef portalRef = parsePortalTableRef(rawObject);
        if (ds == null && portalRef != null && StrUtil.isNotBlank(portalRef.dsKey)) {
            ds = findDatasourceByIdOrCode(portalRef.dsKey);
        }

        // RDB → JDBC（含从 ds_* 前缀推断出的源）
        if (ds != null && PreviewAdapterSupport.isJdbcSource(ds)) {
            String obj = portalRef != null && StrUtil.isNotBlank(portalRef.tableFqn)
                    ? portalRef.tableFqn
                    : StrUtil.blankToDefault(rawObject, rule.getTableName());
            obj = stripLeadingPortalDsSegment(obj);
            if (StrUtil.isBlank(obj)) {
                throw new IllegalArgumentException("JDBC 探数缺少表名（资产主链路 objectName / 规则 tableName）");
            }
            t.engine = "jdbc";
            t.dialect = jdbcDialect(ds);
            t.vaultPath = ds.getVaultPath();
            t.qualifiedSql = jdbcTableRef(obj, ds);
            return t;
        }

        // 湖表 / 默认 → Trino
        t.engine = "trino";
        t.dialect = "trino";
        String catalog = lhProperties.getGravitino() == null ? "iceberg"
                : StrUtil.blankToDefault(lhProperties.getGravitino().getCatalog(), "iceberg");
        String schema = "default";
        String table;

        if (asset != null && StrUtil.isNotBlank(asset.getGravAssetId())) {
            CbGravAssetRef ref = gravAssetRefMapper.selectById(asset.getGravAssetId());
            if (ref != null) {
                if (StrUtil.isNotBlank(ref.getGravCatalog()) && !isPortalDsCatalog(ref.getGravCatalog())) {
                    catalog = ref.getGravCatalog();
                }
                if (StrUtil.isNotBlank(ref.getGravSchema())) {
                    schema = ref.getGravSchema();
                }
                if (StrUtil.isNotBlank(ref.getGravTable())) {
                    table = ref.getGravTable();
                    assertIdents(catalog, schema, table);
                    t.catalog = catalog;
                    t.schema = schema;
                    t.qualifiedSql = GovDqRuleSqlBuilder.qualifyTrino(catalog, schema, table);
                    return t;
                }
            }
        }

        String raw = portalRef != null && StrUtil.isNotBlank(portalRef.tableFqn)
                ? portalRef.tableFqn
                : stripLeadingPortalDsSegment(rawObject);
        if (StrUtil.isBlank(raw)) {
            throw new IllegalArgumentException("规则未绑定表名/资产");
        }
        if (portalRef != null && StrUtil.isNotBlank(portalRef.dsKey) && ds == null) {
            throw new IllegalArgumentException("探数目标含门户数据源前缀 "
                    + portalRef.dsKey + "，但未找到对应 JDBC/湖源；请检查资产主链路数据源");
        }
        GovDqRuleSqlBuilder.assertSafeTableToken(raw.replace("\"", "").replace("`", ""));
        String[] parts = raw.trim().split("\\.");
        if (parts.length >= 3) {
            catalog = parts[parts.length - 3];
            schema = parts[parts.length - 2];
            table = parts[parts.length - 1];
        } else if (parts.length == 2) {
            schema = parts[0];
            table = parts[1];
        } else {
            table = parts[0];
            if (asset != null && StrUtil.isNotBlank(asset.getLayer())) {
                schema = StrUtil.isNotBlank(asset.getDomainCode())
                        ? asset.getLayer() + "_" + asset.getDomainCode()
                        : asset.getLayer();
            } else if (StrUtil.isNotBlank(rule.getLayer())) {
                schema = rule.getLayer().toLowerCase(Locale.ROOT);
            }
        }
        if (isPortalDsCatalog(catalog)) {
            throw new IllegalArgumentException("不能把门户数据源标识 "
                    + catalog + " 当作 Trino catalog；RDB 表应走 JDBC 探数，湖表请绑定 Grav/Iceberg 资产");
        }
        assertIdents(catalog, schema, table);
        t.catalog = catalog;
        t.schema = schema;
        t.qualifiedSql = GovDqRuleSqlBuilder.qualifyTrino(catalog, schema, table);
        return t;
    }

    /** 门户清单/资产 FQN：{@code ds_<id|code>.schema.table} 或 {@code ds_<id|code>.table} */
    private static PortalTableRef parsePortalTableRef(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        String text = raw.trim().replace("`", "").replace("\"", "");
        String[] parts = text.split("\\.");
        if (parts.length < 2 || !isPortalDsCatalog(parts[0])) {
            return null;
        }
        PortalTableRef r = new PortalTableRef();
        r.dsKey = parts[0];
        if (parts.length >= 3) {
            r.tableFqn = parts[parts.length - 2] + "." + parts[parts.length - 1];
        } else {
            r.tableFqn = parts[1];
        }
        return r;
    }

    private static String stripLeadingPortalDsSegment(String raw) {
        if (StrUtil.isBlank(raw)) {
            return raw;
        }
        PortalTableRef ref = parsePortalTableRef(raw);
        return ref != null ? ref.tableFqn : raw.trim();
    }

    /** 门户数据源编码/主键形态：ds_xxx，不是真实 Trino catalog */
    static boolean isPortalDsCatalog(String catalog) {
        if (StrUtil.isBlank(catalog)) {
            return false;
        }
        String c = catalog.trim();
        return c.regionMatches(true, 0, "ds_", 0, 3);
    }

    private LhDatasource findDatasourceByIdOrCode(String key) {
        if (StrUtil.isBlank(key)) {
            return null;
        }
        String k = key.trim();
        LhDatasource byId = datasourceMapper.selectById(k);
        if (byId != null) {
            return byId;
        }
        // objectName 前缀常为 ds_<雪花id>，而 ig_datasource.id 无 ds_ 前缀
        if (isPortalDsCatalog(k) && k.length() > 3) {
            String bare = k.substring(3);
            byId = datasourceMapper.selectById(bare);
            if (byId != null) {
                return byId;
            }
        }
        LhDatasource byCode = datasourceMapper.selectOne(new QueryWrapper<LhDatasource>().lambda()
                .eq(LhDatasource::getDeleteFlag, NOT_DELETE)
                .eq(LhDatasource::getDsCode, k)
                .last("LIMIT 1"));
        if (byCode != null) {
            return byCode;
        }
        if (isPortalDsCatalog(k) && k.length() > 3) {
            return datasourceMapper.selectOne(new QueryWrapper<LhDatasource>().lambda()
                    .eq(LhDatasource::getDeleteFlag, NOT_DELETE)
                    .eq(LhDatasource::getDsCode, k.substring(3))
                    .last("LIMIT 1"));
        }
        return null;
    }

    private static final class PortalTableRef {
        String dsKey;
        String tableFqn;
    }

    private static void assertIdents(String... parts) {
        for (String p : parts) {
            if (!GovDqRuleSqlBuilder.isSafeIdent(p)) {
                throw new IllegalArgumentException("非法标识符: " + p);
            }
        }
    }

    private static String jdbcDialect(LhDatasource ds) {
        String type = PreviewAdapterSupport.dsType(ds);
        if (type.contains("postgres") || "pg".equals(type)) {
            return "postgres";
        }
        if (type.contains("sqlserver")) {
            return "sqlserver";
        }
        if (type.contains("oracle")) {
            return "oracle";
        }
        return "mysql";
    }

    /** 与 {@code JdbcPreviewAdapter} 同口径的表引用。 */
    private static String jdbcTableRef(String objectName, LhDatasource ds) {
        String type = PreviewAdapterSupport.dsType(ds);
        LhIcebergNamespaceNames.SchemaTable st = LhIcebergNamespaceNames.resolveSchemaTable(ds, objectName);
        String schema = st.schema();
        String table = StrUtil.blankToDefault(st.table(), PreviewAdapterSupport.shortName(objectName));
        String[] parts = StrUtil.blankToDefault(objectName, "object").trim().split("\\.");
        if (parts.length >= 3) {
            String cat = parts[parts.length - 3].toLowerCase(Locale.ROOT);
            if ("mysql".equals(cat) || "postgresql".equals(cat) || "postgres".equals(cat)
                    || "pg".equals(cat) || "oracle".equals(cat) || "sqlserver".equals(cat)
                    || isPortalDsCatalog(cat)) {
                schema = parts[parts.length - 2];
                table = parts[parts.length - 1];
            }
        } else if (parts.length == 2 && isPortalDsCatalog(parts[0])) {
            table = parts[1];
            schema = st.schema();
        }
        if (!GovDqRuleSqlBuilder.isSafeIdent(table)
                || (StrUtil.isNotBlank(schema) && !GovDqRuleSqlBuilder.isSafeIdent(schema))) {
            throw new IllegalArgumentException("非法 JDBC 表名: " + objectName);
        }
        if (type.contains("postgres") || "pg".equals(type) || type.contains("oracle")) {
            if (StrUtil.isNotBlank(schema)) {
                return "\"" + schema + "\".\"" + table + "\"";
            }
            return "\"" + table + "\"";
        }
        if (type.contains("sqlserver")) {
            if (StrUtil.isNotBlank(schema)) {
                return "[" + schema + "].[" + table + "]";
            }
            return "[" + table + "]";
        }
        if (StrUtil.isNotBlank(schema) && (ds == null || !schema.equalsIgnoreCase(ds.getDatabaseName()))) {
            return "`" + schema + "`.`" + table + "`";
        }
        return "`" + table + "`";
    }

    private static long firstLong(Map<String, Object> row, String... keys) {
        if (row == null || row.isEmpty()) {
            return 0L;
        }
        for (String k : keys) {
            for (Map.Entry<String, Object> e : row.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(k) && e.getValue() != null) {
                    try {
                        return new BigDecimal(String.valueOf(e.getValue())).longValue();
                    } catch (Exception ignored) {
                        // next
                    }
                }
            }
        }
        // 单列兜底
        if (row.size() == 1) {
            Object v = row.values().iterator().next();
            if (v != null) {
                try {
                    return new BigDecimal(String.valueOf(v)).longValue();
                } catch (Exception ignored) {
                    return 0L;
                }
            }
        }
        return 0L;
    }

    private static String firstStr(Object... vals) {
        for (Object v : vals) {
            if (v != null && StrUtil.isNotBlank(String.valueOf(v))) {
                return String.valueOf(v).trim();
            }
        }
        return null;
    }

    private static final class Target {
        String engine;
        String dialect;
        String catalog;
        String schema;
        String qualifiedSql;
        String vaultPath;
    }
}
