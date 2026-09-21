package vip.xiaonuo.lh.modular.query.support;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.engine.TrinoClient;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.query.entity.CbTrinoCatalogMap;
import vip.xiaonuo.lh.modular.query.mapper.CbTrinoCatalogMapMapper;
import vip.xiaonuo.lh.modular.schemasync.entity.CbGravAssetRef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 即席查询面：配置白名单 ∩ Trino SHOW CATALOGS，再经登记→查询 catalog 映射解析 FQN。
 */
@Slf4j
@Component
public class CpTrinoQueryCatalogService {

    private static final String NOT_DELETE = "NOT_DELETE";

    @Resource
    private LhProperties lhProperties;
    @Resource
    private TrinoClient trinoClient;
    @Resource
    private CbTrinoCatalogMapMapper catalogMapMapper;

    private final AtomicReference<Set<String>> liveCache = new AtomicReference<>(Set.of());
    private final AtomicLong liveCacheAt = new AtomicLong(0L);
    private final AtomicReference<String> liveError = new AtomicReference<>(null);

    /** 白名单 ∩ 实况；Trino 不可达时回退为白名单（避免整树空白，执行仍会失败）。 */
    public Set<String> queryableCatalogs() {
        Set<String> whitelist = parseCsv(lhProperties.getTrino().getQueryCatalogs());
        if (whitelist.isEmpty()) {
            whitelist = Set.of("iceberg");
        }
        Set<String> live = liveCatalogs();
        if (live.isEmpty()) {
            return whitelist;
        }
        Set<String> out = new LinkedHashSet<>();
        for (String w : whitelist) {
            if (containsIgnoreCase(live, w)) {
                out.add(canonical(live, w));
            }
        }
        return out;
    }

    public Set<String> lakeCatalogs() {
        Set<String> lakes = parseCsv(lhProperties.getTrino().getLakeCatalogs());
        return lakes.isEmpty() ? Set.of("iceberg") : lakes;
    }

    public boolean showUnrunnableInTree() {
        return lhProperties.getTrino().isShowUnrunnableInTree();
    }

    /**
     * 解析资产的查询 FQN；无法进入查询面时返回 null。
     */
    public QueryFqn resolveQueryFqn(GovAsset asset, CbGravAssetRef ref) {
        if (ref == null || StrUtil.hasBlank(ref.getGravSchema(), ref.getGravTable())) {
            return null;
        }
        String gravCat = StrUtil.trim(ref.getGravCatalog());
        if (StrUtil.isBlank(gravCat)) {
            return null;
        }
        String queryCat = resolveQueryCatalog(gravCat, asset == null ? null : asset.getEngine(),
                asset == null ? "default" : StrUtil.blankToDefault(asset.getWs(), "default"));
        if (StrUtil.isBlank(queryCat)) {
            return null;
        }
        Set<String> q = queryableCatalogs();
        if (!containsIgnoreCase(q, queryCat)) {
            return null;
        }
        return new QueryFqn(canonical(q, queryCat), ref.getGravSchema().trim(), ref.getGravTable().trim(), gravCat);
    }

    /**
     * Grav 登记名 → Trino 查询名（不校验是否在 Q 中）。
     * <p>只认登记 catalog 本身在 {@code lake-catalogs} 里，或已有启用的 catalog 映射。
     * 资产 {@code engine} 含 iceberg/hive/lake 不能把 JDBC 源库表改写成 {@code iceberg.<schema>.<table>}。
     * {@code engine} 参数保留给调用方，不参与判定。</p>
     */
    public String resolveQueryCatalog(String gravCatalog, String engine, String ws) {
        String grav = StrUtil.trim(gravCatalog);
        if (StrUtil.isBlank(grav)) {
            return null;
        }
        Set<String> lakes = lakeCatalogs();
        if (containsIgnoreCase(lakes, grav)) {
            return canonical(lakes, grav);
        }
        CbTrinoCatalogMap mapped = findMap(ws, grav);
        if (mapped != null && StrUtil.isNotBlank(mapped.getTrinoCatalog())
                && mapped.getEnabled() != null && mapped.getEnabled() == 1) {
            return mapped.getTrinoCatalog().trim();
        }
        // 无映射时仅当登记名本身已是白名单内名才直通（湖表自映射场景）
        Set<String> whitelist = parseCsv(lhProperties.getTrino().getQueryCatalogs());
        if (containsIgnoreCase(whitelist, grav) || containsIgnoreCase(lakes, grav)) {
            return grav;
        }
        return null;
    }

    public boolean isCatalogAllowed(String catalog) {
        if (StrUtil.isBlank(catalog)) {
            return false;
        }
        return containsIgnoreCase(queryableCatalogs(), catalog.trim());
    }

    public String denyMessage(String catalog) {
        String c = StrUtil.blankToDefault(catalog, "(empty)");
        return "数据源未进入即席查询面：" + c
                + "（须在 lh.trino.query-catalogs 白名单且 Trino SHOW CATALOGS 可见；JDBC 登记名 ds_* 默认不可查）";
    }

    public Map<String, Object> surfaceSnapshot() {
        Set<String> whitelist = parseCsv(lhProperties.getTrino().getQueryCatalogs());
        Set<String> live = liveCatalogs();
        Set<String> q = queryableCatalogs();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("whitelist", new ArrayList<>(whitelist));
        out.put("liveCatalogs", new ArrayList<>(live));
        out.put("queryable", new ArrayList<>(q));
        out.put("lakeCatalogs", new ArrayList<>(lakeCatalogs()));
        out.put("showUnrunnableInTree", showUnrunnableInTree());
        out.put("liveError", liveError.get());
        out.put("cacheAgeMs", Math.max(0L, System.currentTimeMillis() - liveCacheAt.get()));
        return out;
    }

    public List<Map<String, Object>> listMaps(String ws) {
        String workspace = StrUtil.blankToDefault(StrUtil.trim(ws), "default");
        List<CbTrinoCatalogMap> rows = catalogMapMapper.selectList(new QueryWrapper<CbTrinoCatalogMap>().lambda()
                .eq(CbTrinoCatalogMap::getDeleteFlag, NOT_DELETE)
                .eq(CbTrinoCatalogMap::getWs, workspace)
                .orderByAsc(CbTrinoCatalogMap::getGravCatalog));
        Set<String> q = queryableCatalogs();
        List<Map<String, Object>> out = new ArrayList<>();
        for (CbTrinoCatalogMap row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", row.getId());
            m.put("ws", row.getWs());
            m.put("gravCatalog", row.getGravCatalog());
            m.put("trinoCatalog", row.getTrinoCatalog());
            m.put("enabled", row.getEnabled() != null && row.getEnabled() == 1);
            m.put("kind", row.getKind());
            m.put("remark", row.getRemark());
            m.put("inQueryable", containsIgnoreCase(q, row.getTrinoCatalog()));
            out.add(m);
        }
        return out;
    }

    /**
     * 联邦源开通：登记 Grav→Trino 映射；须 Trino 已挂载且（可选）写入白名单提示。
     */
    public Map<String, Object> upsertMap(String ws, String gravCatalog, String trinoCatalog,
                                         String kind, Boolean enabled, String remark) {
        String workspace = StrUtil.blankToDefault(StrUtil.trim(ws), "default");
        String grav = StrUtil.trim(gravCatalog);
        String trino = StrUtil.trim(trinoCatalog);
        if (StrUtil.hasBlank(grav, trino)) {
            throw new CommonException("gravCatalog / trinoCatalog 不能为空");
        }
        if (grav.toLowerCase(Locale.ROOT).startsWith("ds_")
                && StrUtil.equalsIgnoreCase(grav, trino)) {
            throw new CommonException("禁止把 Grav 登记名 ds_* 当作 Trino 查询 catalog；请填入真实 Trino catalog");
        }
        Set<String> live = liveCatalogs();
        if (!live.isEmpty() && !containsIgnoreCase(live, trino)) {
            throw new CommonException("Trino 实况无 catalog「" + trino + "」，请先在 Trino 挂载后再开通即席");
        }
        Date now = new Date();
        CbTrinoCatalogMap existing = findMap(workspace, grav);
        if (existing == null) {
            CbTrinoCatalogMap row = new CbTrinoCatalogMap();
            row.setId(IdUtil.getSnowflakeNextIdStr());
            row.setRevision(1);
            row.setStatus("active");
            row.setWs(workspace);
            row.setGravCatalog(grav);
            row.setTrinoCatalog(trino);
            row.setEnabled(enabled == null || enabled ? 1 : 0);
            row.setKind(StrUtil.blankToDefault(kind, "federated"));
            row.setRemark(remark);
            row.setDeleteFlag(NOT_DELETE);
            row.setCreateTime(now);
            row.setUpdateTime(now);
            catalogMapMapper.insert(row);
            return onboardPayload(row, live);
        }
        existing.setTrinoCatalog(trino);
        if (enabled != null) {
            existing.setEnabled(enabled ? 1 : 0);
        }
        if (StrUtil.isNotBlank(kind)) {
            existing.setKind(kind);
        }
        if (remark != null) {
            existing.setRemark(remark);
        }
        existing.setStatus("active");
        existing.setRevision(existing.getRevision() == null ? 1 : existing.getRevision() + 1);
        existing.setUpdateTime(now);
        catalogMapMapper.updateById(existing);
        return onboardPayload(existing, live);
    }

    public void invalidateLiveCache() {
        liveCacheAt.set(0L);
    }

    private Map<String, Object> onboardPayload(CbTrinoCatalogMap row, Set<String> live) {
        Set<String> whitelist = parseCsv(lhProperties.getTrino().getQueryCatalogs());
        boolean inWhitelist = containsIgnoreCase(whitelist, row.getTrinoCatalog());
        boolean inLive = live.isEmpty() || containsIgnoreCase(live, row.getTrinoCatalog());
        boolean enabled = row.getEnabled() != null && row.getEnabled() == 1;
        List<String> checklist = new ArrayList<>();
        checklist.add(inLive ? "✓ Trino 已挂载 " + row.getTrinoCatalog() : "✗ 先在 Trino 创建/挂载 catalog");
        checklist.add(inWhitelist
                ? "✓ 已在 lh.trino.query-catalogs 白名单"
                : "○ 将 " + row.getTrinoCatalog() + " 追加到 lh.trino.query-catalogs 后重启/热更配置");
        checklist.add(enabled ? "✓ 映射已启用" : "○ 映射未启用（enabled=0）");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.getId());
        out.put("gravCatalog", row.getGravCatalog());
        out.put("trinoCatalog", row.getTrinoCatalog());
        out.put("enabled", enabled);
        out.put("kind", row.getKind());
        out.put("inWhitelist", inWhitelist);
        out.put("inLive", inLive);
        out.put("queryable", enabled && inWhitelist && inLive);
        out.put("checklist", checklist);
        out.put("message", enabled && inWhitelist && inLive
                ? "已开通：资产查询 FQN 将使用 " + row.getTrinoCatalog() + ".schema.table"
                : "映射已保存；按 checklist 补齐后即可进入即席查询面");
        return out;
    }

    private Set<String> liveCatalogs() {
        long ttl = lhProperties.getTrino().getCatalogCacheTtlMs();
        long now = System.currentTimeMillis();
        Set<String> cached = liveCache.get();
        if (ttl > 0 && !cached.isEmpty() && now - liveCacheAt.get() < ttl) {
            return cached;
        }
        synchronized (this) {
            if (ttl > 0 && !liveCache.get().isEmpty() && now - liveCacheAt.get() < ttl) {
                return liveCache.get();
            }
            try {
                TrinoClient.ExecuteOptions opts = TrinoClient.ExecuteOptions.job(500);
                opts.source = "lakehouse-portal-catalog-probe";
                Map<String, Object> res = trinoClient.execute("SHOW CATALOGS", opts);
                if (Boolean.TRUE.equals(res.get("degraded"))) {
                    liveError.set(String.valueOf(res.get("message")));
                    liveCacheAt.set(now);
                    return liveCache.get().isEmpty() ? Set.of() : liveCache.get();
                }
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rows = (List<Map<String, Object>>) res.getOrDefault("rows", List.of());
                Set<String> live = new LinkedHashSet<>();
                for (Map<String, Object> row : rows) {
                    Object cat = row.values().stream().findFirst().orElse(null);
                    if (cat != null && StrUtil.isNotBlank(String.valueOf(cat))) {
                        live.add(String.valueOf(cat).trim());
                    }
                }
                liveCache.set(Collections.unmodifiableSet(live));
                liveCacheAt.set(now);
                liveError.set(null);
                return liveCache.get();
            } catch (Exception e) {
                log.warn("SHOW CATALOGS failed: {}", e.getMessage());
                liveError.set(e.getMessage());
                liveCacheAt.set(now);
                return liveCache.get().isEmpty() ? Set.of() : liveCache.get();
            }
        }
    }

    private CbTrinoCatalogMap findMap(String ws, String gravCatalog) {
        return catalogMapMapper.selectOne(new QueryWrapper<CbTrinoCatalogMap>().lambda()
                .eq(CbTrinoCatalogMap::getDeleteFlag, NOT_DELETE)
                .eq(CbTrinoCatalogMap::getWs, StrUtil.blankToDefault(ws, "default"))
                .eq(CbTrinoCatalogMap::getGravCatalog, gravCatalog)
                .last("limit 1"));
    }

    private static Set<String> parseCsv(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (StrUtil.isBlank(raw)) {
            return out;
        }
        for (String p : raw.split("[,;\\s]+")) {
            if (StrUtil.isNotBlank(p)) {
                out.add(p.trim());
            }
        }
        return out;
    }

    private static boolean containsIgnoreCase(Set<String> set, String v) {
        if (set == null || StrUtil.isBlank(v)) {
            return false;
        }
        for (String s : set) {
            if (s != null && s.equalsIgnoreCase(v)) {
                return true;
            }
        }
        return false;
    }

    private static String canonical(Set<String> set, String v) {
        for (String s : set) {
            if (s != null && s.equalsIgnoreCase(v)) {
                return s;
            }
        }
        return v;
    }

    /** 查询面三元组 */
    public static final class QueryFqn {
        public final String catalog;
        public final String schema;
        public final String table;
        public final String gravCatalog;

        public QueryFqn(String catalog, String schema, String table, String gravCatalog) {
            this.catalog = catalog;
            this.schema = schema;
            this.table = table;
            this.gravCatalog = gravCatalog;
        }

        public String fqn() {
            return catalog + "." + schema + "." + table;
        }

        public String sampleSql() {
            String quoted = quoteIdent(catalog) + "." + quoteIdent(schema) + "." + quoteIdent(table);
            String sch = schema.toLowerCase(Locale.ROOT);
            if (sch.startsWith("ods")) {
                return "SELECT *\nFROM " + quoted + "\nWHERE dt >= date_add('day', -7, current_date)\nLIMIT 100";
            }
            return "SELECT *\nFROM " + quoted + "\nLIMIT 100";
        }

        private static String quoteIdent(String id) {
            if (id == null) {
                return "\"\"";
            }
            if (id.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                return id;
            }
            return "\"" + id.replace("\"", "\"\"") + "\"";
        }
    }
}
