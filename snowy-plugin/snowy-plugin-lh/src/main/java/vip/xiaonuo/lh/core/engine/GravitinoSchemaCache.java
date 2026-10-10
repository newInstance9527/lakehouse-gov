package vip.xiaonuo.lh.core.engine;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.modular.schemasync.entity.CbGravAssetRef;
import vip.xiaonuo.lh.modular.schemasync.mapper.CbGravAssetRefMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gravitino 只读 schema 缓存（§23 / 双 Catalog L1）。
 * <p>
 * 成功读刷新内存 TTL；Grav 不可用时降级：内存 → {@code cb_grav_asset_ref}。
 * 写路径（建表/登记）不走缓存，仍由 {@link GravitinoAvailability} 拦截。
 */
@Slf4j
@Component
public class GravitinoSchemaCache {

    private static final String NOT_DELETE = "NOT_DELETE";
    private static final String SRC_MEMORY = "memory_cache";
    private static final String SRC_DB = "cb_grav_asset_ref";

    private final ConcurrentHashMap<String, CacheEntry> store = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong puts = new AtomicLong();
    private final AtomicLong fallbacks = new AtomicLong();

    @Resource
    private LhProperties lhProperties;
    @Resource
    private CbGravAssetRefMapper gravAssetRefMapper;

    public boolean enabled() {
        LhProperties.Gravitino g = lhProperties.getGravitino();
        return g == null || g.isSchemaCacheEnabled();
    }

    public boolean fallbackWhenDown() {
        LhProperties.Gravitino g = lhProperties.getGravitino();
        return g == null || g.isSchemaCacheFallbackWhenDown();
    }

    private long ttlMs() {
        LhProperties.Gravitino g = lhProperties.getGravitino();
        long ttl = g != null ? g.getSchemaCacheTtlMs() : 300_000L;
        return ttl > 0 ? ttl : 300_000L;
    }

    public void putNames(String kind, String key, List<String> names) {
        if (!enabled() || names == null) {
            return;
        }
        store.put(kind + ":" + key, new CacheEntry(new ArrayList<>(names), System.currentTimeMillis() + ttlMs()));
        puts.incrementAndGet();
    }

    @SuppressWarnings("unchecked")
    public List<String> getNames(String kind, String key) {
        if (!enabled()) {
            return null;
        }
        CacheEntry e = store.get(kind + ":" + key);
        if (e == null || e.expired()) {
            if (e != null) {
                store.remove(kind + ":" + key, e);
            }
            misses.incrementAndGet();
            return null;
        }
        hits.incrementAndGet();
        return new ArrayList<>((List<String>) e.value);
    }

    public void putTable(GravitinoClient.GravTable table) {
        if (!enabled() || table == null || StrUtil.hasBlank(table.metalake, table.catalog, table.schema, table.name)) {
            return;
        }
        String k = tableKey(table.metalake, table.catalog, table.schema, table.name);
        GravitinoClient.GravTable copy = copyTable(table);
        copy.cacheSource = null;
        store.put("table:" + k, new CacheEntry(copy, System.currentTimeMillis() + ttlMs()));
        puts.incrementAndGet();
    }

    public GravitinoClient.GravTable getTable(String metalake, String catalog, String schema, String table) {
        if (!enabled()) {
            return null;
        }
        String k = tableKey(metalake, catalog, schema, table);
        CacheEntry e = store.get("table:" + k);
        if (e == null || e.expired()) {
            if (e != null) {
                store.remove("table:" + k, e);
            }
            misses.incrementAndGet();
            return null;
        }
        hits.incrementAndGet();
        GravitinoClient.GravTable t = copyTable((GravitinoClient.GravTable) e.value);
        t.cacheSource = SRC_MEMORY;
        return t;
    }

    /**
     * Grav 读失败时的降级：内存（含过期条目宽限）→ 业务库投影。
     */
    public GravitinoClient.GravTable fallbackTable(String metalake, String catalog, String schema, String table) {
        if (!fallbackWhenDown()) {
            return null;
        }
        GravitinoClient.GravTable mem = getTableAllowStale(metalake, catalog, schema, table);
        if (mem != null) {
            fallbacks.incrementAndGet();
            return mem;
        }
        GravitinoClient.GravTable db = loadFromDb(metalake, catalog, schema, table);
        if (db != null) {
            fallbacks.incrementAndGet();
            putTable(db);
            db.cacheSource = SRC_DB;
        }
        return db;
    }

    public List<String> fallbackNames(String kind, String key) {
        if (!fallbackWhenDown()) {
            return null;
        }
        List<String> fresh = getNames(kind, key);
        if (fresh != null) {
            fallbacks.incrementAndGet();
            return fresh;
        }
        CacheEntry e = store.get(kind + ":" + key);
        if (e != null && e.value instanceof List<?> list) {
            hits.incrementAndGet();
            fallbacks.incrementAndGet();
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                out.add(String.valueOf(o));
            }
            return out;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private GravitinoClient.GravTable getTableAllowStale(String metalake, String catalog, String schema, String table) {
        String k = tableKey(metalake, catalog, schema, table);
        CacheEntry e = store.get("table:" + k);
        if (e == null || !(e.value instanceof GravitinoClient.GravTable)) {
            return null;
        }
        hits.incrementAndGet();
        GravitinoClient.GravTable t = copyTable((GravitinoClient.GravTable) e.value);
        t.cacheSource = SRC_MEMORY + (e.expired() ? "_stale" : "");
        return t;
    }

    private GravitinoClient.GravTable loadFromDb(String metalake, String catalog, String schema, String table) {
        try {
            CbGravAssetRef ref = gravAssetRefMapper.selectOne(new QueryWrapper<CbGravAssetRef>().lambda()
                    .eq(CbGravAssetRef::getDeleteFlag, NOT_DELETE)
                    .eq(CbGravAssetRef::getGravMetalake, metalake)
                    .eq(CbGravAssetRef::getGravCatalog, catalog)
                    .eq(CbGravAssetRef::getGravSchema, schema)
                    .eq(CbGravAssetRef::getGravTable, table)
                    .last("LIMIT 1"));
            if (ref == null || StrUtil.isBlank(ref.getColumnsJson())) {
                return null;
            }
            GravitinoClient.GravTable gt = new GravitinoClient.GravTable();
            gt.metalake = metalake;
            gt.catalog = catalog;
            gt.schema = schema;
            gt.name = table;
            gt.location = ref.getLocationUri();
            gt.auditVersion = ref.getGravRevision() == null ? 0L : ref.getGravRevision();
            gt.cacheSource = SRC_DB;
            JSONArray arr = JSONUtil.parseArray(ref.getColumnsJson());
            for (int i = 0; i < arr.size(); i++) {
                JSONObject c = arr.getJSONObject(i);
                if (c == null) {
                    continue;
                }
                GravitinoClient.GravColumn col = new GravitinoClient.GravColumn();
                col.name = c.getStr("name");
                col.type = c.getStr("type");
                col.nullable = !Boolean.FALSE.equals(c.getBool("nullable"));
                col.comment = c.getStr("comment");
                gt.columns.add(col);
            }
            return gt;
        } catch (Exception e) {
            log.debug("grav schema cache db fallback soft-fail: {}", e.getMessage());
            return null;
        }
    }

    /** 写操作后失效相关 list 缓存（表级条目保留至 TTL，避免读抖动）。 */
    public void invalidateSchema(String metalake, String catalog, String schema) {
        if (StrUtil.hasBlank(metalake, catalog)) {
            return;
        }
        store.remove("schemas:" + metalake + "." + catalog);
        if (StrUtil.isNotBlank(schema)) {
            store.remove("tables:" + metalake + "." + catalog + "." + schema);
        }
        store.remove("catalogs:" + metalake);
    }

    public void invalidateTable(String metalake, String catalog, String schema, String table) {
        invalidateSchema(metalake, catalog, schema);
        if (StrUtil.isNotBlank(table)) {
            store.remove("table:" + tableKey(metalake, catalog, schema, table));
        }
    }

    public Map<String, Object> stats() {
        long now = System.currentTimeMillis();
        int live = 0;
        int stale = 0;
        for (CacheEntry e : store.values()) {
            if (e.expired(now)) {
                stale++;
            } else {
                live++;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled());
        m.put("fallbackWhenDown", fallbackWhenDown());
        m.put("ttlMs", ttlMs());
        m.put("entries", store.size());
        m.put("live", live);
        m.put("stale", stale);
        m.put("hits", hits.get());
        m.put("misses", misses.get());
        m.put("puts", puts.get());
        m.put("fallbacks", fallbacks.get());
        return m;
    }

    private static String tableKey(String metalake, String catalog, String schema, String table) {
        return StrUtil.blankToDefault(metalake, "").toLowerCase(Locale.ROOT) + "."
                + StrUtil.blankToDefault(catalog, "").toLowerCase(Locale.ROOT) + "."
                + StrUtil.blankToDefault(schema, "").toLowerCase(Locale.ROOT) + "."
                + StrUtil.blankToDefault(table, "").toLowerCase(Locale.ROOT);
    }

    private static GravitinoClient.GravTable copyTable(GravitinoClient.GravTable src) {
        GravitinoClient.GravTable t = new GravitinoClient.GravTable();
        t.metalake = src.metalake;
        t.catalog = src.catalog;
        t.schema = src.schema;
        t.name = src.name;
        t.comment = src.comment;
        t.location = src.location;
        t.auditVersion = src.auditVersion;
        t.cacheSource = src.cacheSource;
        t.partitionKeys = src.partitionKeys == null ? new ArrayList<>() : new ArrayList<>(src.partitionKeys);
        t.columns = new ArrayList<>();
        if (src.columns != null) {
            for (GravitinoClient.GravColumn c : src.columns) {
                GravitinoClient.GravColumn col = new GravitinoClient.GravColumn();
                col.name = c.name;
                col.type = c.type;
                col.nullable = c.nullable;
                col.comment = c.comment;
                col.masked = c.masked;
                if (c.properties != null) {
                    col.properties = new LinkedHashMap<>(c.properties);
                }
                t.columns.add(col);
            }
        }
        return t;
    }

    private static final class CacheEntry {
        final Object value;
        final long expiresAt;

        CacheEntry(Object value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }

        boolean expired() {
            return expired(System.currentTimeMillis());
        }

        boolean expired(long now) {
            return now > expiresAt;
        }
    }
}
